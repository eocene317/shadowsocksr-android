package com.github.shadowsocks

import java.nio.charset.Charset

import android.app.{Activity, TaskStackBuilder}
import android.content._
import android.content.pm.PackageManager
import android.nfc.NfcAdapter.CreateNdefMessageCallback
import android.nfc.{NdefMessage, NdefRecord, NfcAdapter, NfcEvent}
import android.os.{Bundle, Handler}
import android.provider.Settings
import android.support.v7.app.{AlertDialog, AppCompatActivity}
import android.support.v7.widget.RecyclerView.ViewHolder
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.support.v7.widget._
import android.support.v7.widget.helper.ItemTouchHelper
import android.support.v7.widget.helper.ItemTouchHelper.SimpleCallback
import android.text.style.TextAppearanceSpan
import android.text.{SpannableStringBuilder, Spanned, TextUtils}
import android.view._
import android.widget.{CheckedTextView, ImageView, LinearLayout, Toast}
import android.net.Uri
import com.github.clans.fab.{FloatingActionButton, FloatingActionMenu}
import com.github.shadowsocks.ShadowsocksApplication.app
import com.github.shadowsocks.aidl.IShadowsocksServiceCallback
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.utils.{Key, Parser, TrafficMonitor, Utils}
import com.github.shadowsocks.widget.UndoSnackbarManager
import net.glxn.qrgen.android.QRCode

import scala.collection.mutable.ArrayBuffer

final class ProfileManagerActivity extends AppCompatActivity with OnMenuItemClickListener with ServiceBoundContext
  with View.OnClickListener with CreateNdefMessageCallback {

  private final class ProfileViewHolder(val view: View) extends RecyclerView.ViewHolder(view)
    with View.OnClickListener with View.OnKeyListener {

    var item: Profile = _
    private val text = itemView.findViewById(android.R.id.text1).asInstanceOf[CheckedTextView]
    itemView.setOnClickListener(this)
    itemView.setOnKeyListener(this)

    {
      val shareBtn = itemView.findViewById(R.id.share)
      shareBtn.setOnClickListener(_ => {
        val url = item.toString
        if (isNfcBeamEnabled) {
          nfcAdapter.setNdefPushMessageCallback(ProfileManagerActivity.this,ProfileManagerActivity.this)
          nfcShareItem = url.getBytes(Charset.forName("UTF-8"))
        }
        val image = new ImageView(ProfileManagerActivity.this)
        image.setLayoutParams(new LinearLayout.LayoutParams(-1, -1))
        val qrcode = QRCode.from(url)
          .withSize(Utils.dpToPx(ProfileManagerActivity.this, 250), Utils.dpToPx(ProfileManagerActivity.this, 250))
          .asInstanceOf[QRCode].bitmap()
        image.setImageBitmap(qrcode)

        val dialog = new AlertDialog.Builder(ProfileManagerActivity.this, R.style.Theme_Material_Dialog_Alert)
          .setCancelable(true)
          .setPositiveButton(R.string.close, null)
          .setNegativeButton(R.string.copy_url, ((_, _) =>
            clipboard.setPrimaryClip(ClipData.newPlainText(null, url))): DialogInterface.OnClickListener)
          .setView(image)
          .setTitle(R.string.share)
          .create()
        if (!isNfcAvailable) dialog.setMessage(getString(R.string.share_message_without_nfc))
        else if (!isNfcBeamEnabled) {
          dialog.setMessage(getString(R.string.share_message_nfc_disabled))
          dialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.turn_on_nfc),
            ((_, _) => startActivity(new Intent(Settings.ACTION_NFC_SETTINGS))): DialogInterface.OnClickListener)
        } else {
          dialog.setMessage(getString(R.string.share_message))
          dialog.setOnDismissListener(_ =>
            nfcAdapter.setNdefPushMessageCallback(null, ProfileManagerActivity.this))
        }
        dialog.show()
      })
      shareBtn.setOnLongClickListener(_ => {
        Utils.positionToast(Toast.makeText(ProfileManagerActivity.this, R.string.share, Toast.LENGTH_SHORT), shareBtn,
          getWindow, 0, Utils.dpToPx(ProfileManagerActivity.this, 8)).show
        true
      })
    }

    def updateText(txTotal: Long = 0, rxTotal: Long = 0) {
      val builder = new SpannableStringBuilder
      val tx = item.tx + txTotal
      val rx = item.rx + rxTotal
      builder.append(item.name)
      if (tx != 0 || rx != 0) {
        val start = builder.length
        builder.append(getString(R.string.stat_profiles,
          TrafficMonitor.formatTraffic(tx), TrafficMonitor.formatTraffic(rx)))
        builder.setSpan(new TextAppearanceSpan(ProfileManagerActivity.this, android.R.style.TextAppearance_Small),
          start + 1, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      handler.post(() => text.setText(builder))
    }

    def bind(item: Profile) {
      this.item = item
      updateText()
      if (item.id == app.profileId) {
        text.setChecked(true)
        selectedItem = this
      } else {
        text.setChecked(false)
        if (selectedItem eq this) selectedItem = null
      }
    }

    def onClick(v: View) {
      app.switchProfile(item.id)
      finish
    }

    def onKey(v: View, keyCode: Int, event: KeyEvent) = if (event.getAction == KeyEvent.ACTION_DOWN) keyCode match {
      case KeyEvent.KEYCODE_DPAD_LEFT =>
        val index = getAdapterPosition
        if (index >= 0) {
          profilesAdapter.remove(index)
          undoManager.remove(index, item)
          true
        } else false
      case _ => false
    } else false
  }

  private class ProfilesAdapter extends RecyclerView.Adapter[ProfileViewHolder] {
    var profiles = new ArrayBuffer[Profile]
    profiles ++= app.profileManager.getAllProfiles.getOrElse(List.empty[Profile])

    def getItemCount = profiles.length

    def onBindViewHolder(vh: ProfileViewHolder, i: Int) = vh.bind(profiles(i))

    def onCreateViewHolder(vg: ViewGroup, i: Int) =
      new ProfileViewHolder(LayoutInflater.from(vg.getContext).inflate(R.layout.layout_profiles_item, vg, false))

    def add(item: Profile) {
      undoManager.flush
      val pos = getItemCount
      profiles += item
      notifyItemInserted(pos)
    }

    def move(from: Int, to: Int) {
      undoManager.flush
      val step = if (from < to) 1 else -1
      val first = profiles(from)
      var previousOrder = profiles(from).userOrder
      for (i <- from until to by step) {
        val next = profiles(i + step)
        val order = next.userOrder
        next.userOrder = previousOrder
        previousOrder = order
        profiles(i) = next
        app.profileManager.updateProfile(next)
      }
      first.userOrder = previousOrder
      profiles(to) = first
      app.profileManager.updateProfile(first)
      notifyItemMoved(from, to)
    }

    def remove(pos: Int) {
      profiles.remove(pos)
      notifyItemRemoved(pos)
    }
    def undo(actions: Iterator[(Int, Profile)]) = for ((index, item) <- actions) {
      profiles.insert(index, item)
      notifyItemInserted(index)
    }
    def commit(actions: Iterator[(Int, Profile)]) = for ((index, item) <- actions) {
      app.profileManager.delProfile(item.id)
      if (item.id == app.profileId) app.profileId(-1)
    }
  }

  private var selectedItem: ProfileViewHolder = _
  private val handler = new Handler

  private var menu : FloatingActionMenu = _

  private lazy val profilesAdapter = new ProfilesAdapter
  private var undoManager: UndoSnackbarManager[Profile] = _

  private lazy val clipboard = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]

  private var nfcAdapter : NfcAdapter = _
  private var nfcShareItem: Array[Byte] = _
  private var isNfcAvailable: Boolean = _
  private var isNfcEnabled: Boolean = _
  private var isNfcBeamEnabled: Boolean = _

  private val REQUEST_QRCODE = 1

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.layout_profiles)

    val toolbar = findViewById(R.id.toolbar).asInstanceOf[Toolbar]
    toolbar.setTitle(R.string.profiles)
    toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
    toolbar.setNavigationOnClickListener(_ => {
      val intent = getParentActivityIntent
      if (shouldUpRecreateTask(intent) || isTaskRoot)
        TaskStackBuilder.create(this).addNextIntentWithParentStack(intent).startActivities()
      else finish()
    })
    toolbar.inflateMenu(R.menu.profile_manager_menu)
    toolbar.setOnMenuItemClickListener(this)

    initFab()

    app.profileManager.setProfileAddedListener(profilesAdapter.add)
    val profilesList = findViewById(R.id.profilesList).asInstanceOf[RecyclerView]
    val layoutManager = new LinearLayoutManager(this)
    profilesList.setLayoutManager(layoutManager)
    profilesList.setItemAnimator(new DefaultItemAnimator)
    profilesList.setAdapter(profilesAdapter)
    layoutManager.scrollToPosition(profilesAdapter.profiles.zipWithIndex.collectFirst {
      case (profile, i) if profile.id == app.profileId => i
    }.getOrElse(-1))
    undoManager = new UndoSnackbarManager[Profile](profilesList, profilesAdapter.undo, profilesAdapter.commit)
    new ItemTouchHelper(new SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
      ItemTouchHelper.START | ItemTouchHelper.END) {
      def onSwiped(viewHolder: ViewHolder, direction: Int) = {
        val index = viewHolder.getAdapterPosition
        profilesAdapter.remove(index)
        undoManager.remove(index, viewHolder.asInstanceOf[ProfileViewHolder].item)
      }
      def onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder) = {
        profilesAdapter.move(viewHolder.getAdapterPosition, target.getAdapterPosition)
        true
      }
    }).attachToRecyclerView(profilesList)

    attachService(new IShadowsocksServiceCallback.Stub {
      def stateChanged(state: Int, profileName: String, msg: String) = () // ignore
      def trafficUpdated(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) =
        if (selectedItem != null) selectedItem.updateText(txTotal, rxTotal)
    })

    if (app.settings.getBoolean(Key.profileTip, true)) {
      app.editor.putBoolean(Key.profileTip, false).apply
      new AlertDialog.Builder(this, R.style.Theme_Material_Dialog_Alert).setTitle(R.string.profile_manager_dialog)
        .setMessage(R.string.profile_manager_dialog_content).setPositiveButton(R.string.gotcha, null).create.show
    }

    val intent = getIntent
    if (intent != null) handleShareIntent(intent)
  }

  def initFab() {
    menu = findViewById(R.id.menu).asInstanceOf[FloatingActionMenu]
    menu.setClosedOnTouchOutside(true)
    val dm = AppCompatDrawableManager.get
    val manualAddFAB = findViewById(R.id.fab_manual_add).asInstanceOf[FloatingActionButton]
    manualAddFAB.setImageDrawable(dm.getDrawable(this, R.drawable.ic_content_create))
    manualAddFAB.setOnClickListener(this)
    val qrcodeAddFAB = findViewById(R.id.fab_qrcode_add).asInstanceOf[FloatingActionButton]
    qrcodeAddFAB.setImageDrawable(dm.getDrawable(this, R.drawable.ic_image_camera_alt))
    qrcodeAddFAB.setOnClickListener(this)
    val nfcAddFAB = findViewById(R.id.fab_nfc_add).asInstanceOf[FloatingActionButton]
    nfcAddFAB.setImageDrawable(dm.getDrawable(this, R.drawable.ic_device_nfc))
    nfcAddFAB.setOnClickListener(this)
    val importAddFAB = findViewById(R.id.fab_import_add).asInstanceOf[FloatingActionButton]
    importAddFAB.setImageDrawable(dm.getDrawable(this, R.drawable.ic_content_paste))
    importAddFAB.setOnClickListener(this)
    menu.setOnMenuToggleListener(opened => if (opened) qrcodeAddFAB.setVisibility(
      if (getPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) View.VISIBLE else View.GONE))
  }


  override def onResume() {
    super.onResume()
    updateNfcState()
  }

  override def onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShareIntent(intent)
  }

  override def onClick(v: View){
    v.getId match {
      case R.id.fab_manual_add =>
        menu.toggle(true)
        val profile = app.profileManager.createProfile()
        app.profileManager.updateProfile(profile)
        app.switchProfile(profile.id)
        finish
      case R.id.fab_qrcode_add =>
        try {
            menu.toggle(false)
            val intent = new Intent("com.google.zxing.client.android.SCAN")
            intent.putExtra("SCAN_MODE", "QR_CODE_MODE")

            startActivityForResult(intent, 0);
        } catch {
            case _ : Throwable =>
                val marketUri = Uri.parse("market://details?id=com.google.zxing.client.android")
                val marketIntent = new Intent(Intent.ACTION_VIEW, marketUri)
                startActivity(marketIntent)
        }
      case R.id.fab_nfc_add =>
        menu.toggle(true)
        val dialog = new AlertDialog.Builder(ProfileManagerActivity.this, R.style.Theme_Material_Dialog_Alert)
          .setCancelable(true)
          .setPositiveButton(R.string.gotcha, null)
          .setTitle(R.string.add_profile_nfc_hint_title)
          .create()
        if (!isNfcBeamEnabled) {
          dialog.setMessage(getString(R.string.share_message_nfc_disabled))
          dialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.turn_on_nfc), ((_, _) =>
              startActivity(new Intent(Settings.ACTION_NFC_SETTINGS))
            ): DialogInterface.OnClickListener)
        } else {
          dialog.setMessage(getString(R.string.add_profile_nfc_hint))
        }
        dialog.show
      case R.id.fab_import_add =>
        menu.toggle(true)
        if (clipboard.hasPrimaryClip) {
          val profiles_normal = Parser.findAll(clipboard.getPrimaryClip.getItemAt(0).getText).toList
          val profiles_ssr = Parser.findAll_ssr(clipboard.getPrimaryClip.getItemAt(0).getText).toList
          val profiles = profiles_normal ::: profiles_ssr
          if (profiles.nonEmpty) {
            val dialog = new AlertDialog.Builder(this, R.style.Theme_Material_Dialog_Alert)
              .setTitle(R.string.add_profile_dialog)
              .setPositiveButton(android.R.string.yes, ((_, _) =>
                profiles.foreach(app.profileManager.createProfile)): DialogInterface.OnClickListener)
              .setNeutralButton(R.string.dr, ((_, _) =>
                profiles.foreach(app.profileManager.createProfile_dr)): DialogInterface.OnClickListener)
              .setNegativeButton(android.R.string.no, ((_, _) => finish()): DialogInterface.OnClickListener)
              .setMessage(profiles.mkString("\n"))
              .create()
            dialog.show()
            return
          }
        }
        Toast.makeText(this, R.string.action_import_err, Toast.LENGTH_SHORT).show
    }
  }

  def updateNfcState() {
    isNfcAvailable = false
    isNfcEnabled = false
    isNfcBeamEnabled = false
    nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    if (nfcAdapter != null) {
      isNfcAvailable = true
      if (nfcAdapter.isEnabled) {
        isNfcEnabled = true
        if (nfcAdapter.isNdefPushEnabled) {
          isNfcBeamEnabled = true
          nfcAdapter.setNdefPushMessageCallback(null, ProfileManagerActivity.this)
        }
      }
    }
  }

  def handleShareIntent(intent: Intent) {
    val sharedStr = intent.getAction match {
      case Intent.ACTION_VIEW => intent.getData.toString
      case NfcAdapter.ACTION_NDEF_DISCOVERED =>
        val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMsgs != null && rawMsgs.nonEmpty)
          new String(rawMsgs(0).asInstanceOf[NdefMessage].getRecords()(0).getPayload)
        else null
      case _ => null
    }
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
      if (requestCode == 0) {
          if (resultCode == Activity.RESULT_OK) {
              val contents = data.getStringExtra("SCAN_RESULT");
              if (TextUtils.isEmpty(contents)) return
              val profiles_normal = Parser.findAll(contents).toList
              val profiles_ssr = Parser.findAll_ssr(contents).toList
              val profiles = profiles_ssr ::: profiles_normal
              if (profiles.isEmpty) {
                finish()
                return
              }
              val dialog = new AlertDialog.Builder(this, R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.add_profile_dialog)
                .setPositiveButton(android.R.string.yes, ((_, _) =>
                  profiles.foreach(app.profileManager.createProfile)): DialogInterface.OnClickListener)
                .setNeutralButton(R.string.dr, ((_, _) =>
                  profiles.foreach(app.profileManager.createProfile_dr)): DialogInterface.OnClickListener)
                .setNegativeButton(android.R.string.no, ((_, _) => finish()): DialogInterface.OnClickListener)
                .setMessage(profiles.mkString("\n"))
                .create()
              dialog.show()
          }
          if(resultCode == Activity.RESULT_CANCELED){
              //handle cancel
          }
      }
  }

  override def onStart() {
    super.onStart()
    registerCallback
  }
  override def onStop() {
    super.onStop()
    unregisterCallback
  }

  override def onDestroy {
    detachService()
    undoManager.flush
    app.profileManager.setProfileAddedListener(null)
    super.onDestroy
  }

  override def onBackPressed() {
    if (menu.isOpened) menu.close(true) else super.onBackPressed()
  }

  def createNdefMessage(nfcEvent: NfcEvent) =
    new NdefMessage(Array(new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, nfcShareItem, Array[Byte](), nfcShareItem)))

  def onMenuItemClick(item: MenuItem): Boolean = item.getItemId match {
    case R.id.action_export =>
      app.profileManager.getAllProfiles match {
        case Some(profiles) =>
          clipboard.setPrimaryClip(ClipData.newPlainText(null, profiles.mkString("\n")))
          Toast.makeText(this, R.string.action_export_msg, Toast.LENGTH_SHORT).show
        case _ => Toast.makeText(this, R.string.action_export_err, Toast.LENGTH_SHORT).show
      }
      true
    case _ => false
  }
}
