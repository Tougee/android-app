package one.mixin.android.ui.media.pager.transcript

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.net.toFile
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.Player
import com.tbruyelle.rxpermissions2.RxPermissions
import com.uber.autodispose.autoDispose
import dagger.hilt.android.AndroidEntryPoint
import one.mixin.android.R
import one.mixin.android.databinding.ActivityMediaPagerBinding
import one.mixin.android.databinding.ItemPagerVideoLayoutBinding
import one.mixin.android.databinding.ViewDragImageBottomBinding
import one.mixin.android.databinding.ViewDragVideoBottomBinding
import one.mixin.android.extension.async
import one.mixin.android.extension.backgroundDrawable
import one.mixin.android.extension.checkInlinePermissions
import one.mixin.android.extension.copyFromInputStream
import one.mixin.android.extension.createGifTemp
import one.mixin.android.extension.createImageTemp
import one.mixin.android.extension.createPngTemp
import one.mixin.android.extension.fadeOut
import one.mixin.android.extension.getFilePath
import one.mixin.android.extension.getPublicPicturePath
import one.mixin.android.extension.getUriForFile
import one.mixin.android.extension.isAutoRotate
import one.mixin.android.extension.isLandscape
import one.mixin.android.extension.openAsUrlOrQrScan
import one.mixin.android.extension.openPermissionSetting
import one.mixin.android.extension.realSize
import one.mixin.android.extension.statusBarHeight
import one.mixin.android.extension.supportsPie
import one.mixin.android.extension.textColor
import one.mixin.android.extension.toast
import one.mixin.android.repository.ConversationRepository
import one.mixin.android.session.Session
import one.mixin.android.ui.PipVideoView
import one.mixin.android.ui.common.BaseActivity
import one.mixin.android.ui.media.pager.MediaPagerActivity
import one.mixin.android.ui.qr.QRCodeProcessor
import one.mixin.android.util.AnimationProperties
import one.mixin.android.util.SensorOrientationChangeNotifier
import one.mixin.android.util.SystemUIManager
import one.mixin.android.util.VideoPlayer
import one.mixin.android.vo.ChatHistoryMessageItem
import one.mixin.android.vo.MediaStatus
import one.mixin.android.vo.absolutePath
import one.mixin.android.vo.isImage
import one.mixin.android.vo.isLive
import one.mixin.android.vo.isMedia
import one.mixin.android.vo.isVideo
import one.mixin.android.vo.loadVideoOrLive
import one.mixin.android.vo.saveToLocal
import one.mixin.android.widget.BottomSheet
import one.mixin.android.widget.PhotoView.DismissFrameLayout
import one.mixin.android.widget.gallery.MimeType
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import kotlin.math.min

@AndroidEntryPoint
class TranscriptMediaPagerActivity : BaseActivity(), DismissFrameLayout.OnDismissListener, SensorOrientationChangeNotifier.Listener {
    private lateinit var colorDrawable: ColorDrawable

    private val pipVideoView by lazy {
        PipVideoView.getInstance()
    }

    private val processor = QRCodeProcessor()

    private val adapter: TranscriptMediaPagerAdapter by lazy {
        TranscriptMediaPagerAdapter(this, this@TranscriptMediaPagerActivity, mediaPagerAdapterListener)
    }

    override fun getDefaultThemeId(): Int {
        return R.style.AppTheme_Photo
    }

    override fun getNightThemeId(): Int {
        return R.style.AppTheme_Night_Photo
    }

    @Inject
    lateinit var conversationRepository: ConversationRepository
    private lateinit var binding: ActivityMediaPagerBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        skipSystemUi = true
        postponeEnterTransition()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMediaPagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.sharedElementEnterTransition.duration = SHARED_ELEMENT_TRANSITION_DURATION
        window.sharedElementExitTransition.duration = SHARED_ELEMENT_TRANSITION_DURATION
        supportsPie {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        SystemUIManager.fitsSystem(window)
        binding.root.doOnPreDraw {
            SystemUIManager.lightUI(window, false)
        }
        colorDrawable = ColorDrawable(Color.BLACK)
        binding.viewPager.backgroundDrawable = colorDrawable
        binding.viewPager.adapter = adapter
        binding.viewPager.registerOnPageChangeCallback(onPageChangeCallback)
        VideoPlayer.player().setCycle(false)

        // workaround with ViewPager2 restore state bug
        binding.viewPager.isSaveEnabled = false

        binding.lockTv.setOnClickListener(onLockClickListener)

        SensorOrientationChangeNotifier.init(this, requestedOrientation)

        // todo check
        binding.viewPager.setCurrentItem(0, false)

        loadData()
    }

    override fun onResume() {
        super.onResume()
        SensorOrientationChangeNotifier.resume()
    }

    override fun onPause() {
        super.onPause()
        SensorOrientationChangeNotifier.pause()
    }

    override fun onStop() {
        super.onStop()
        binding.lockTv.removeCallbacks(hideLockRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        processor.close()
        SensorOrientationChangeNotifier.reset()
        binding.viewPager.unregisterOnPageChangeCallback(onPageChangeCallback)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val enable = ev?.pointerCount ?: 0 < 2
        binding.viewPager.isUserInputEnabled = enable
        return super.dispatchTouchEvent(ev)
    }

    override fun onOrientationChange(oldOrientation: Int, newOrientation: Int) {
        if (!isAutoRotate()) return

        showLock()

        if (isLocked) return

        changeOrientation(newOrientation)
    }

    @Synchronized
    private fun changeOrientation(orientation: Int) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            return
        }

        requestedOrientation = when (orientation) {
            270 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            SystemUIManager.showSystemUI(window)
        } else {
            SystemUIManager.hideSystemUI(window)
        }
        findViewPagerChildByTag {
            ItemPagerVideoLayoutBinding.bind(it).playerView.switchFullscreen(orientation == 90 || orientation == 270)
            (it as DismissFrameLayout).resetChildren()
        }
    }

    @SuppressLint("RestrictedApi")
    private fun loadData() = lifecycleScope.launchWhenCreated {
        val initialIndex = conversationRepository.indexTranscriptMediaMessages(transcriptId, messageId)
        adapter.list = conversationRepository.getTranscriptMediaMessage(transcriptId)
        adapter.initialPos = initialIndex
        binding.viewPager.setCurrentItem(initialIndex, false)

        val messageItem = adapter.getItem(initialIndex)
        if (messageItem.isVideo() || messageItem.isLive()) {
            checkPip()
            messageItem.loadVideoOrLive {
                VideoPlayer.player().start()
            }
        }
    }

    private fun checkPip() {
        if (pipVideoView.shown) {
            pipVideoView.close(messageId != VideoPlayer.player().mId)
        }
    }

    private fun showVideoBottom(messageItem: ChatHistoryMessageItem) {
        val builder = BottomSheet.Builder(this)
        val view = View.inflate(
            ContextThemeWrapper(this, R.style.Custom),
            R.layout.view_drag_video_bottom,
            null,
        )
        val binding = ViewDragVideoBottomBinding.bind(view)
        builder.setCustomView(view)
        val bottomSheet = builder.create()
        binding.showInChat.isVisible = false
        binding.saveVideo.setOnClickListener {
            RxPermissions(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .autoDispose(stopScope)
                .subscribe(
                    { granted ->
                        if (granted) {
                            messageItem.saveToLocal(this@TranscriptMediaPagerActivity)
                        } else {
                            openPermissionSetting()
                        }
                    },
                    {
                        toast(R.string.Save_failure)
                    },
                )
            bottomSheet.dismiss()
        }
        binding.share.setOnClickListener {
            messageItem.absolutePath()?.let {
                shareMedia(true, it)
            }
            bottomSheet.dismiss()
        }
        binding.cancel.setOnClickListener { bottomSheet.dismiss() }
        bottomSheet.show()
    }

    private fun showImageBottom(item: ChatHistoryMessageItem, pagerItemView: View) {
        val builder = BottomSheet.Builder(this)
        val view = View.inflate(
            ContextThemeWrapper(this, R.style.Custom),
            R.layout.view_drag_image_bottom,
            null,
        )
        val binding = ViewDragImageBottomBinding.bind(view)
        builder.setCustomView(view)
        val bottomSheet = builder.create()
        binding.showInChat.isVisible = false
        binding.save.setOnClickListener {
            RxPermissions(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .autoDispose(stopScope)
                .subscribe(
                    { granted ->
                        if (granted) {
                            async {
                                val path = item.absolutePath()
                                if (path == null) {
                                    toast(R.string.Save_failure)
                                    return@async
                                }
                                val file = Uri.parse(path).toFile()
                                if (!file.exists()) {
                                    toast(R.string.File_does_not_exist)
                                    return@async
                                }
                                val outFile = when {
                                    item.mediaMimeType.equals(
                                        MimeType.GIF.toString(),
                                        true,
                                    ) -> this@TranscriptMediaPagerActivity.getPublicPicturePath().createGifTemp(
                                        false,
                                    )
                                    item.mediaMimeType.equals(MimeType.PNG.toString()) ->
                                        this@TranscriptMediaPagerActivity.getPublicPicturePath().createPngTemp(
                                            false,
                                        )
                                    else -> this@TranscriptMediaPagerActivity.getPublicPicturePath().createImageTemp(
                                        noMedia = false,
                                    )
                                }
                                outFile.copyFromInputStream(FileInputStream(file))
                                MediaScannerConnection.scanFile(this@TranscriptMediaPagerActivity, arrayOf(outFile.toString()), null, null)
                                runOnUiThread { toast(getString(R.string.Save_to, outFile.absolutePath)) }
                            }
                        } else {
                            openPermissionSetting()
                        }
                    },
                    {
                        toast(R.string.Save_failure)
                    },
                )
            bottomSheet.dismiss()
        }
        binding.shareImage.setOnClickListener {
            item.mediaUrl?.let {
                shareMedia(false, it)
            }
            bottomSheet.dismiss()
        }
        binding.decode.setOnClickListener {
            decodeQRCode(pagerItemView as ViewGroup)
            bottomSheet.dismiss()
        }
        binding.cancel.setOnClickListener { bottomSheet.dismiss() }
        bottomSheet.show()
    }

    @Suppress("DEPRECATION")
    private fun decodeQRCode(viewGroup: ViewGroup) {
        val imageView = viewGroup.getChildAt(0)
        val bitmap = if (imageView is ImageView) {
            val bitmapDrawable = imageView.drawable as? BitmapDrawable
            if (bitmapDrawable == null) {
                toast(R.string.can_not_recognize_qr_code)
                return
            } else {
                bitmapDrawable.bitmap
            }
        } else {
            imageView.isDrawingCacheEnabled = true
            imageView.buildDrawingCache()
            imageView.drawingCache
        }
        if (bitmap != null) {
            processor.detect(
                lifecycleScope,
                bitmap,
                onSuccess = { result ->
                    result.openAsUrlOrQrScan(this, supportFragmentManager, lifecycleScope)
                },
                onFailure = { toast(R.string.can_not_recognize_qr_code) },
                onComplete = {
                    if (imageView !is ImageView) {
                        imageView.isDrawingCacheEnabled = false
                    }
                },
            )
        } else {
            toast(R.string.can_not_recognize_qr_code)
            if (imageView !is ImageView) {
                imageView.isDrawingCacheEnabled = false
            }
        }
    }

    private fun shareMedia(isVideo: Boolean, url: String) {
        var uri: Uri
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            uri = Uri.parse(url)
            if (ContentResolver.SCHEME_FILE == uri.scheme) {
                val path = uri.getFilePath(this@TranscriptMediaPagerActivity)
                if (path == null) {
                    toast(R.string.File_does_not_exist)
                    return
                }
                uri = getUriForFile(File(path))
                putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            type = if (isVideo) "video/*" else "image/*"
        }
        val name = getString(if (isVideo) R.string.Video else R.string.Photo)
        val chooser = Intent.createChooser(sendIntent, getString(R.string.share_to, name))
        val resInfoList = packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfoList.forEach {
            val packageName = it.activityInfo.packageName
            grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        startActivity(chooser)
    }

    private var pipAnimationInProgress = false
    private fun switchToPip(messageItem: ChatHistoryMessageItem, view: View) {
        if (!checkPipPermission() || pipAnimationInProgress) {
            return
        }
        pipAnimationInProgress = true
        findViewPagerChildByTag { windowView ->
            val videoAspectRatioLayout =
                ItemPagerVideoLayoutBinding.bind(windowView).playerView.videoAspectRatio
            val rect = PipVideoView.getPipRect(videoAspectRatioLayout.aspectRatio)
            val isLandscape = isLandscape()
            if (isLandscape) {
                val screenHeight = realSize().y
                if (rect.width > screenHeight) {
                    val ratio = rect.width / rect.height
                    rect.height = screenHeight / ratio
                    rect.width = screenHeight.toFloat()
                }
            }
            val width = if (isLandscape) windowView.height else windowView.width
            val scale = (if (isLandscape) rect.height else rect.width) / width
            val animatorSet = AnimatorSet()
            val position = IntArray(2)
            videoAspectRatioLayout.getLocationOnScreen(position)
            val changedTextureView = pipVideoView.show(
                videoAspectRatioLayout.aspectRatio,
                videoAspectRatioLayout.videoRotation,
                "", //
                messageItem.messageId,
                messageItem.isVideo(),
                MediaPagerActivity.MediaSource.ChatHistory, //
                messageItem.absolutePath(),
            )

            val videoTexture = view.findViewById<TextureView>(R.id.video_texture)
            animatorSet.playTogether(
                ObjectAnimator.ofInt(colorDrawable, AnimationProperties.COLOR_DRAWABLE_ALPHA, 0),
                ObjectAnimator.ofFloat(videoTexture, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(videoTexture, View.SCALE_Y, scale),
                ObjectAnimator.ofFloat(
                    videoAspectRatioLayout,
                    View.TRANSLATION_X,
                    rect.x - videoAspectRatioLayout.x -
                        this.realSize().x * (1f - scale) / 2,
                ),
                ObjectAnimator.ofFloat(
                    videoAspectRatioLayout,
                    View.TRANSLATION_Y,
                    rect.y - videoAspectRatioLayout.y +
                        this.statusBarHeight() - (videoAspectRatioLayout.height - rect.height) / 2,
                ),
            )
            animatorSet.interpolator = DecelerateInterpolator()
            animatorSet.duration = 250
            animatorSet.addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        windowView.findViewById<View>(R.id.pip_iv).fadeOut()
                        windowView.findViewById<View>(R.id.close_iv).fadeOut()
                        if (windowView.findViewById<View>(R.id.live_tv).isEnabled) {
                            windowView.findViewById<View>(R.id.live_tv).fadeOut()
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        pipAnimationInProgress = false
                        if (messageItem.isVideo() && VideoPlayer.player().player.playbackState == Player.STATE_IDLE) {
                            VideoPlayer.player()
                                .loadVideo(messageItem.absolutePath()!!, messageItem.messageId, true)
                            VideoPlayer.player().setVideoTextureView(changedTextureView)
                            VideoPlayer.player().pause()
                        } else {
                            VideoPlayer.player().setVideoTextureView(changedTextureView)
                        }
                        dismiss()
                    }
                },
            )
            animatorSet.start()
        }
    }

    private var permissionAlert: AlertDialog? = null

    private fun checkPipPermission() =
        checkInlinePermissions {
            if (permissionAlert != null && permissionAlert!!.isShowing) return@checkInlinePermissions

            permissionAlert = AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.call_pip_permission)
                .setPositiveButton(R.string.Settings) { dialog, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                    dialog.dismiss()
                }.show()
        }

    private fun dismiss() {
        binding.viewPager.visibility = View.INVISIBLE
        overridePendingTransition(0, 0)
        super.finish()
    }

    private fun setStartPostTransition(sharedView: View) {
        sharedView.doOnPreDraw { startPostponedEnterTransition() }
    }

    private fun downloadMedia(position: Int): Boolean {
        val currMessageItem = getMessageItemByPosition(position) ?: return false
        if (currMessageItem.mediaStatus == MediaStatus.CANCELED.name) return false
        if (currMessageItem.isMedia() && currMessageItem.mediaUrl == null) {
            // viewModel.downloadByMessageId(currMessageItem.messageId)
            return true
        }
        return false
    }

    private fun getMessageItemByPosition(position: Int): ChatHistoryMessageItem? =
        try {
            adapter.getItem(position)
        } catch (e: IndexOutOfBoundsException) {
            null
        }

    private inline fun findViewPagerChildByTag(
        pos: Int = binding.viewPager.currentItem,
        crossinline action: (v: ViewGroup) -> Unit,
    ) {
        if (isFinishing) return
        val id = getMessageItemByPosition(pos)?.messageId ?: return
        val v = binding.viewPager.findViewWithTag<DismissFrameLayout>("$PREFIX$id")
        if (v != null) {
            action(v as ViewGroup)
        }
    }

    private fun loadVideoMessage(messageItem: ChatHistoryMessageItem) {
        if (messageItem.isVideo() || messageItem.isLive()) {
            messageItem.loadVideoOrLive {
                val view =
                    binding.viewPager.findViewWithTag<DismissFrameLayout>("$PREFIX${messageItem.messageId}")
                if (view != null) {
                    ItemPagerVideoLayoutBinding.bind(view).playerView.player = VideoPlayer.player().player
                }
            }
        }
    }

    private var isLocked = false

    private fun showLock() {
        binding.lockTv.isVisible = true
        binding.lockTv.removeCallbacks(hideLockRunnable)
        binding.lockTv.postDelayed(hideLockRunnable, 3000)
    }

    private fun checkOrientation() {
        if (isAutoRotate() && !isLocked) {
            val sensorIsLandscape = SensorOrientationChangeNotifier.isLandscape()
            val activityLandscape = isLandscape()
            if (sensorIsLandscape != activityLandscape) {
                changeOrientation(SensorOrientationChangeNotifier.orientation)
            }
        }
    }

    private val hideLockRunnable = Runnable {
        binding.lockTv.isVisible = false
    }

    private val onLockClickListener = View.OnClickListener {
        isLocked = !isLocked
        if (isLocked) {
            binding.lockTv.text = getString(R.string.Click_to_unlock)
            binding.lockTv.textColor = getColor(R.color.colorAccent)
        } else {
            binding.lockTv.text = getString(R.string.Click_to_lock)
            binding.lockTv.textColor = getColor(R.color.white)
        }
        binding.lockTv.removeCallbacks(hideLockRunnable)
        binding.lockTv.postDelayed(hideLockRunnable, 3000)
        checkOrientation()
    }

    private val onPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            binding.lockTv.removeCallbacks(hideLockRunnable)
            binding.lockTv.post(hideLockRunnable)

            if (downloadMedia(position)) return

            val messageItem = getMessageItemByPosition(position) ?: return
            if (VideoPlayer.player().mId != messageItem.messageId && !pipVideoView.shown) {
                VideoPlayer.player().stop()
                VideoPlayer.player().pause()
            }
            if (messageItem.isVideo() || messageItem.isLive()) {
                checkPip()
            }
            loadVideoMessage(messageItem)
        }
    }

    private var inDismissState = false
    private var controllerVisibleBeforeDismiss = false

    override fun onDismissProgress(progress: Float) {
        if (progress > 0 && !inDismissState) {
            inDismissState = true
            val messageItem = getMessageItemByPosition(binding.viewPager.currentItem) ?: return
            if (messageItem.isLive() || messageItem.isVideo()) {
                findViewPagerChildByTag {
                    val playerView = ItemPagerVideoLayoutBinding.bind(it).playerView
                    controllerVisibleBeforeDismiss = playerView.useController && playerView.videoAspectRatio.isVisible
                    playerView.hideController()
                }
            }
        }
        colorDrawable.alpha = min(ALPHA_MAX, ((1 - progress) * ALPHA_MAX).toInt())
    }

    override fun onDismiss() {
        inDismissState = false
        finishAfterTransition()
    }

    override fun onCancel() {
        if (inDismissState) {
            inDismissState = false
            val messageItem = getMessageItemByPosition(binding.viewPager.currentItem) ?: return
            if (messageItem.isLive() || messageItem.isVideo()) {
                findViewPagerChildByTag {
                    val playerView = ItemPagerVideoLayoutBinding.bind(it).playerView
                    if (controllerVisibleBeforeDismiss) {
                        playerView.showController(false)
                    } else {
                        playerView.hideController()
                    }
                }
            }
        }
        colorDrawable.alpha = ALPHA_MAX
    }

    override fun finishAfterTransition() {
        findViewPagerChildByTag {
            ItemPagerVideoLayoutBinding.bind(it).playerView.hideController()
        }
        super.finishAfterTransition()
    }

    override fun finish() {
        if (!pipVideoView.shown) {
            VideoPlayer.destroy()
        }
        super.finish()
        overridePendingTransition(0, R.anim.scale_out)
    }

    private val mediaPagerAdapterListener = object : MediaPagerAdapterListener {
        override fun onClick(messageItem: ChatHistoryMessageItem) {
            finishAfterTransition()
        }

        override fun onLongClick(messageItem: ChatHistoryMessageItem, view: View) {
            if (messageItem.isImage()) {
                showImageBottom(messageItem, view)
            } else if (messageItem.isVideo()) {
                showVideoBottom(messageItem)
            }
        }

        override fun onCircleProgressClick(messageItem: ChatHistoryMessageItem) {
            when (messageItem.mediaStatus) {
                MediaStatus.CANCELED.name -> {
                    if (Session.getAccountId() == messageItem.userId) {
                    } else {
                    }
                }
                MediaStatus.PENDING.name -> {
                }
            }
        }

        override fun onReadyPostTransition(view: View) {
            setStartPostTransition(view)
        }

        override fun switchToPin(messageItem: ChatHistoryMessageItem, view: View) {
            switchToPip(messageItem, view)
        }

        override fun finishAfterTransition() {
            this@TranscriptMediaPagerActivity.finishAfterTransition()
        }

        override fun switchFullscreen() {
            val isLandscape = this@TranscriptMediaPagerActivity.isLandscape()
            val orientation = if (isLandscape) 0 else 270
            this@TranscriptMediaPagerActivity.changeOrientation(orientation)
        }
    }

    private val messageId by lazy {
        intent.getStringExtra(MESSAGE_ID) as String
    }

    private val transcriptId by lazy {
        intent.getStringExtra(TRANSCRIPT_ID) as String
    }

    companion object {
        private const val MESSAGE_ID = "message_id"
        private const val TRANSCRIPT_ID = "transcript_id"
        private const val ALPHA_MAX = 0xFF

        const val PREFIX = "media"
        const val PAGE_SIZE = 3

        private const val SHARED_ELEMENT_TRANSITION_DURATION = 200L

        fun show(
            activity: Activity,
            imageView: View,
            transcriptId: String,
            messageId: String,
        ) {
            val intent = Intent(activity, TranscriptMediaPagerActivity::class.java).apply {
                putExtra(TRANSCRIPT_ID, transcriptId)
                putExtra(MESSAGE_ID, messageId)
            }
            activity.startActivity(
                intent,
                ActivityOptions.makeSceneTransitionAnimation(
                    activity,
                    imageView,
                    "transition",
                ).toBundle(),
            )
        }
    }
}
