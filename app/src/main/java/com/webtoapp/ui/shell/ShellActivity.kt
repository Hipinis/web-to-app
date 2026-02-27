package com.webtoapp.ui.shell

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.webtoapp.WebToAppApplication
import com.webtoapp.core.activation.ActivationResult
import com.webtoapp.core.adblock.AdBlocker
import com.webtoapp.core.shell.ShellConfig
import com.webtoapp.core.webview.LongPressHandler
import com.webtoapp.core.i18n.Strings
import com.webtoapp.core.webview.WebViewCallbacks
import com.webtoapp.data.model.Announcement
import com.webtoapp.data.model.LrcData
import com.webtoapp.data.model.LrcLine
import com.webtoapp.data.model.ScriptRunTime
import com.webtoapp.data.model.WebViewConfig
import com.webtoapp.ui.components.ForcedRunCountdownOverlay
import com.webtoapp.ui.components.LongPressMenuSheet
import com.webtoapp.ui.components.VirtualNavigationBar
import com.webtoapp.ui.components.StatusBarOverlay
import com.webtoapp.ui.components.announcement.AnnouncementDialog
import com.webtoapp.ui.theme.ShellTheme
import com.webtoapp.ui.theme.LocalIsDarkTheme
import com.webtoapp.ui.splash.ActivationDialog
import com.webtoapp.util.DownloadHelper
import com.webtoapp.core.webview.TranslateBridge
import com.webtoapp.core.forcedrun.ForcedRunConfig
import com.webtoapp.core.forcedrun.ForcedRunManager
import com.webtoapp.core.forcedrun.ForcedRunMode
import com.webtoapp.core.forcedrun.ForcedRunPermissionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.HashSet

/**
 * Shell Activity - 用于独立 WebApp 运行
 * 从 app_config.json 读取配置并显示 WebView
 * 【厂长专属：Pro级底层防重叠与百万级广告熔断修复版】
 */
class ShellActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    
    // --- 厂长定制：存储 11.txt 提纯后的域名黑名单 ---
    val adBlockSet = HashSet<String>()
    
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null

    private var immersiveFullscreenEnabled: Boolean = false
    private var showStatusBarInFullscreen: Boolean = false 
    private var translateBridge: TranslateBridge? = null
    
    private var originalOrientationBeforeFullscreen: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    
    private var statusBarColorMode: String = "THEME"
    private var statusBarCustomColor: String? = null
    private var statusBarDarkIcons: Boolean? = null
    private var statusBarBackgroundType: String = "COLOR"
    private var statusBarBackgroundImage: String? = null
    private var statusBarBackgroundAlpha: Float = 1.0f
    private var statusBarHeightDp: Int = 0
    private var forceHideSystemUi: Boolean = false
    private var forcedRunConfig: ForcedRunConfig? = null
    private val forcedRunManager by lazy { ForcedRunManager.getInstance(this) }

    private fun applyStatusBarColor(
        colorMode: String,
        customColor: String?,
        darkIcons: Boolean?,
        isDarkTheme: Boolean
    ) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        when (colorMode) {
            "TRANSPARENT" -> {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                val useDarkIcons = darkIcons ?: !isDarkTheme
                controller.isAppearanceLightStatusBars = useDarkIcons
            }
            "CUSTOM" -> {
                val color = try {
                    android.graphics.Color.parseColor(customColor ?: "#FFFFFF")
                } catch (e: Exception) {
                    android.graphics.Color.WHITE
                }
                window.statusBarColor = color
                val useDarkIcons = darkIcons ?: isColorLight(color)
                controller.isAppearanceLightStatusBars = useDarkIcons
            }
            else -> {
                if (isDarkTheme) {
                    window.statusBarColor = android.graphics.Color.parseColor("#1C1B1F")
                    controller.isAppearanceLightStatusBars = false
                } else {
                    window.statusBarColor = android.graphics.Color.parseColor("#FFFBFE")
                    controller.isAppearanceLightStatusBars = true
                }
            }
        }
        controller.isAppearanceLightNavigationBars = controller.isAppearanceLightStatusBars
    }
    
    private fun isColorLight(color: Int): Boolean {
        val red = android.graphics.Color.red(color)
        val green = android.graphics.Color.green(color)
        val blue = android.graphics.Color.blue(color)
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
        return luminance > 0.5
    }

    private fun applyImmersiveFullscreen(enabled: Boolean, hideNavBar: Boolean = true, isDarkTheme: Boolean = false) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = 
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                if (enabled) {
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    val shouldShowStatusBar = if (forceHideSystemUi) false else showStatusBarInFullscreen
                    if (shouldShowStatusBar) {
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        controller.show(WindowInsetsCompat.Type.statusBars())
                        if (statusBarBackgroundType == "IMAGE") {
                            window.statusBarColor = android.graphics.Color.TRANSPARENT
                            val useDarkIcons = statusBarDarkIcons ?: !isDarkTheme
                            controller.isAppearanceLightStatusBars = useDarkIcons
                        } else {
                            when (statusBarColorMode) {
                                "CUSTOM" -> {
                                    val color = try {
                                        android.graphics.Color.parseColor(statusBarCustomColor ?: "#000000")
                                    } catch (e: Exception) { android.graphics.Color.BLACK }
                                    window.statusBarColor = color
                                    val useDarkIcons = statusBarDarkIcons ?: isColorLight(color)
                                    controller.isAppearanceLightStatusBars = useDarkIcons
                                }
                                "TRANSPARENT" -> {
                                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                                    val useDarkIcons = statusBarDarkIcons ?: !isDarkTheme
                                    controller.isAppearanceLightStatusBars = useDarkIcons
                                }
                                else -> {
                                    if (isDarkTheme) {
                                        window.statusBarColor = android.graphics.Color.parseColor("#1C1B1F")
                                        controller.isAppearanceLightStatusBars = false
                                    } else {
                                        window.statusBarColor = android.graphics.Color.parseColor("#FFFBFE")
                                        controller.isAppearanceLightStatusBars = true
                                    }
                                }
                            }
                        }
                    } else {
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                    }
                    
                    if (hideNavBar || forceHideSystemUi) {
                        controller.hide(WindowInsetsCompat.Type.navigationBars())
                    }
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    applyStatusBarColor(statusBarColorMode, statusBarCustomColor, statusBarDarkIcons, isDarkTheme)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ShellActivity", "applyImmersiveFullscreen failed", e)
        }
    }

    fun onForcedRunStateChanged(active: Boolean, config: ForcedRunConfig?) {
        forcedRunConfig = config
        forceHideSystemUi = active && config?.blockSystemUI == true
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { startLockTask() } catch (e: Exception) {}
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { stopLockTask() } catch (e: Exception) {}
            }
        }
        applyImmersiveFullscreen(customView != null || immersiveFullscreenEnabled || forceHideSystemUi)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val hardwareController = com.webtoapp.core.forcedrun.ForcedRunHardwareController.getInstance(this)
        if (hardwareController.isBlockVolumeKeys) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        if (hardwareController.isBlockPowerKey && event.keyCode == KeyEvent.KEYCODE_POWER) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (forcedRunManager.handleKeyEvent(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val hardwareController = com.webtoapp.core.forcedrun.ForcedRunHardwareController.getInstance(this)
        if (hardwareController.isBlockVolumeKeys) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val hardwareController = com.webtoapp.core.forcedrun.ForcedRunHardwareController.getInstance(this)
        if (hardwareController.isBlockTouch) return true
        return super.dispatchTouchEvent(ev)
    }
    
    private var pendingDownload: PendingDownload? = null
    private data class PendingDownload(val url: String, val userAgent: String, val contentDisposition: String, val mimeType: String, val contentLength: Long)

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }
    
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            pendingDownload?.let { download ->
                DownloadHelper.handleDownload(
                    context = this,
                    url = download.url,
                    userAgent = download.userAgent,
                    contentDisposition = download.contentDisposition,
                    mimeType = download.mimeType,
                    contentLength = download.contentLength,
                    method = DownloadHelper.DownloadMethod.DOWNLOAD_MANAGER,
                    scope = lifecycleScope
                )
            }
        } else {
            Toast.makeText(this, Strings.storagePermissionRequired, Toast.LENGTH_SHORT).show()
            pendingDownload?.let { download ->
                DownloadHelper.openInBrowser(this, download.url)
            }
        }
        pendingDownload = null
    }
    
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        pendingPermissionRequest?.let { request ->
            if (allGranted) request.grant(request.resources) else request.deny()
            pendingPermissionRequest = null
        }
    }
    
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.values.any { it }
        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    
    fun handlePermissionRequest(request: PermissionRequest) {
        val resources = request.resources
        val androidPermissions = mutableListOf<String>()
        resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPermissions.add(android.Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        if (androidPermissions.isEmpty()) {
            request.grant(resources)
        } else {
            pendingPermissionRequest = request
            permissionLauncher.launch(androidPermissions.toTypedArray())
        }
    }
    
    fun handleGeolocationPermission(origin: String?, callback: GeolocationPermissions.Callback?) {
        pendingGeolocationOrigin = origin
        pendingGeolocationCallback = callback
        locationPermissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    
    fun handleDownloadWithPermission(url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DownloadHelper.handleDownload(
                context = this,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                method = DownloadHelper.DownloadMethod.DOWNLOAD_MANAGER,
                scope = lifecycleScope
            )
            return
        }
        
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            DownloadHelper.handleDownload(
                context = this,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                method = DownloadHelper.DownloadMethod.DOWNLOAD_MANAGER,
                scope = lifecycleScope
            )
        } else {
            pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType, contentLength)
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val tempConfig = WebToAppApplication.shellMode.getConfig()
            val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" } catch (e: Exception) { "1.0.0" }
            com.webtoapp.core.shell.ShellLogger.init(this, tempConfig?.appName ?: "ShellApp", versionName)
        } catch (e: Exception) { }
        
        try { enableEdgeToEdge() } catch (e: Exception) {}
        
        super.onCreate(savedInstanceState)

        val config = WebToAppApplication.shellMode.getConfig()
        if (config == null) {
            Toast.makeText(this, Strings.appConfigLoadFailed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        forcedRunConfig = config.forcedRunConfig
        
        try {
            val hardwareController = com.webtoapp.core.forcedrun.ForcedRunHardwareController.getInstance(this)
            hardwareController.setTargetActivity(this)
        } catch (e: Exception) {}
        
        if (config.forcedRunConfig?.enabled == true) {
            try {
                forcedRunManager.setTargetActivity(packageName, this::class.java.name)
                forcedRunManager.setOnStateChangedCallback { active, forcedConfig -> 
                    runOnUiThread { onForcedRunStateChanged(active, forcedConfig) } 
                }
            } catch (e: Exception) {}
        }

        // --- 厂长定制：异步预加载广告规则 ---
        Thread {
            try {
                assets.open("adblock_rules.txt").bufferedReader().useLines { lines ->
                    lines.forEach { if (it.isNotBlank()) adBlockSet.add(it.trim()) }
                }
            } catch (e: Exception) { 
                android.util.Log.e("ProBlocker", "Rules Load Failed") 
            }
        }.start()

        config.autoStartConfig?.let { autoStartConfig ->
            try {
                val autoStartManager = com.webtoapp.core.autostart.AutoStartManager(this)
                autoStartManager.setBootStart(0L, autoStartConfig.bootStartEnabled)
                if (autoStartConfig.scheduledStartEnabled) {
                    autoStartManager.setScheduledStart(0L, true, autoStartConfig.scheduledTime, autoStartConfig.scheduledDays)
                }
            } catch (e: Exception) {}
        }
        
        if (config.isolationEnabled && config.isolationConfig != null) {
            try {
                val isolationConfig = config.isolationConfig.toIsolationConfig()
                val isolationManager = com.webtoapp.core.isolation.IsolationManager.getInstance(this)
                isolationManager.initialize(isolationConfig)
            } catch (e: Exception) {}
        }
        
        if (config.backgroundRunEnabled) {
            try {
                val bgConfig = config.backgroundRunConfig
                com.webtoapp.core.background.BackgroundRunService.start(
                    context = this, appName = config.appName, notificationTitle = bgConfig?.notificationTitle?.ifEmpty { null }, notificationContent = bgConfig?.notificationContent?.ifEmpty { null }, showNotification = bgConfig?.showNotification ?: true, keepCpuAwake = bgConfig?.keepCpuAwake ?: true
                )
            } catch (e: Exception) {}
        }
        
        try {
            @Suppress("DEPRECATION")
            setTaskDescription(android.app.ActivityManager.TaskDescription(config.appName))
        } catch (e: Exception) {}
        
        try { requestNotificationPermissionIfNeeded() } catch (e: Exception) {}
        
        statusBarColorMode = config.webViewConfig.statusBarColorMode
        statusBarCustomColor = config.webViewConfig.statusBarColor
        statusBarDarkIcons = config.webViewConfig.statusBarDarkIcons
        statusBarBackgroundType = config.webViewConfig.statusBarBackgroundType
        statusBarBackgroundImage = config.webViewConfig.statusBarBackgroundImage
        statusBarBackgroundAlpha = config.webViewConfig.statusBarBackgroundAlpha
        statusBarHeightDp = config.webViewConfig.statusBarHeightDp
        showStatusBarInFullscreen = config.webViewConfig.showStatusBarInFullscreen
        immersiveFullscreenEnabled = config.webViewConfig.hideToolbar
        
        try { applyImmersiveFullscreen(immersiveFullscreenEnabled) } catch (e: Exception) {}

        setContent {
            ShellTheme(themeTypeName = config.themeType, darkModeSetting = config.darkMode) {
                val isDarkTheme = com.webtoapp.ui.theme.LocalIsDarkTheme.current
                LaunchedEffect(isDarkTheme, statusBarColorMode) {
                    if (!immersiveFullscreenEnabled) {
                        applyStatusBarColor(statusBarColorMode, statusBarCustomColor, statusBarDarkIcons, isDarkTheme)
                    }
                }
                
                ShellScreen(
                    config = config,
                    onWebViewCreated = { wv ->
                        try {
                            webView = wv
                            translateBridge = TranslateBridge(wv, lifecycleScope)
                            wv.addJavascriptInterface(translateBridge!!, TranslateBridge.JS_INTERFACE_NAME)
                            val downloadBridge = com.webtoapp.core.webview.DownloadBridge(this@ShellActivity, lifecycleScope)
                            wv.addJavascriptInterface(downloadBridge, com.webtoapp.core.webview.DownloadBridge.JS_INTERFACE_NAME)
                            val nativeBridge = com.webtoapp.core.webview.NativeBridge(this@ShellActivity, lifecycleScope)
                            wv.addJavascriptInterface(nativeBridge, com.webtoapp.core.webview.NativeBridge.JS_INTERFACE_NAME)
                        } catch (e: Exception) {}
                    },
                    onFileChooser = { callback, _ ->
                        filePathCallback = callback
                        fileChooserLauncher.launch("*/*")
                        true
                    },
                    onShowCustomView = { view, callback ->
                        customView = view
                        customViewCallback = callback
                        showCustomView(view)
                    },
                    onHideCustomView = { hideCustomView() },
                    onFullscreenModeChanged = { enabled ->
                        immersiveFullscreenEnabled = enabled
                        if (customView == null) applyImmersiveFullscreen(enabled)
                    },
                    onForcedRunStateChanged = { active, forcedConfig -> onForcedRunStateChanged(active, forcedConfig) },
                    statusBarBackgroundType = statusBarBackgroundType,
                    statusBarBackgroundColor = statusBarCustomColor,
                    statusBarBackgroundImage = statusBarBackgroundImage,
                    statusBarBackgroundAlpha = statusBarBackgroundAlpha,
                    statusBarHeightDp = statusBarHeightDp
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (forcedRunManager.handleKeyEvent(KeyEvent.KEYCODE_BACK)) {
                    Toast.makeText(this@ShellActivity, Strings.cannotExitDuringForcedRun, Toast.LENGTH_SHORT).show()
                    return
                }
                when {
                    customView != null -> hideCustomView()
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun showCustomView(view: View) {
        originalOrientationBeforeFullscreen = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val decorView = window.decorView as FrameLayout
        decorView.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyImmersiveFullscreen(true)
    }

    private fun hideCustomView() {
        customView?.let { view ->
            val decorView = window.decorView as FrameLayout
            decorView.removeView(view)
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
            requestedOrientation = originalOrientationBeforeFullscreen
            originalOrientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            applyImmersiveFullscreen(immersiveFullscreenEnabled)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen(customView != null || immersiveFullscreenEnabled || forceHideSystemUi)
    }

    override fun onPause() {
        super.onPause()
        android.webkit.CookieManager.getInstance().flush()
    }
    
    override fun onDestroy() {
        android.webkit.CookieManager.getInstance().flush()
        webView?.destroy()
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    config: ShellConfig,
    onWebViewCreated: (WebView) -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback?) -> Unit,
    onHideCustomView: () -> Unit,
    onFullscreenModeChanged: (Boolean) -> Unit,
    onForcedRunStateChanged: (Boolean, ForcedRunConfig?) -> Unit,
    statusBarBackgroundType: String = "COLOR",
    statusBarBackgroundColor: String? = null,
    statusBarBackgroundImage: String? = null,
    statusBarBackgroundAlpha: Float = 1.0f,
    statusBarHeightDp: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as android.app.Activity
    val activation = WebToAppApplication.activation
    val announcement = WebToAppApplication.announcement
    val adBlocker = WebToAppApplication.adBlock
    val forcedRunManager = remember { ForcedRunManager.getInstance(context) }
    val forcedRunActive by forcedRunManager.isInForcedRunMode.collectAsState()
    val forcedRunRemainingMs by forcedRunManager.remainingTimeMs.collectAsState()
    var forcedRunBlocked by remember { mutableStateOf(false) }
    var forcedRunBlockedMessage by remember { mutableStateOf("") }
    
    var showForcedRunPermissionDialog by remember { mutableStateOf(false) }
    var forcedRunPermissionChecked by remember { mutableStateOf(false) }

    val appType = config.appType.trim().uppercase()
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showActivationDialog by remember { mutableStateOf(false) }
    var showAnnouncementDialog by remember { mutableStateOf(false) }
    
    var adBlockCurrentlyEnabled by remember { mutableStateOf(config.adBlockEnabled) }
    var isActivated by remember { mutableStateOf(!config.activationEnabled) }
    var isActivationChecked by remember { mutableStateOf(!config.activationEnabled) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    var splashMediaExists by remember { mutableStateOf(false) }
    
    LaunchedEffect(config.splashEnabled) {
        if (config.splashEnabled) {
            val extension = if (config.splashType == "VIDEO") "mp4" else "png"
            val assetPath = "splash_media.$extension"
            val encryptedPath = "$assetPath.enc"
            val hasEncrypted = try { context.assets.open(encryptedPath).close(); true } catch (e: Exception) { false }
            val hasNormal = try { context.assets.open(assetPath).close(); true } catch (e: Exception) { false }
            splashMediaExists = hasEncrypted || hasNormal
        } else {
            splashMediaExists = false
        }
    }
    
    var showSplash by remember { mutableStateOf(config.splashEnabled) }
    
    LaunchedEffect(splashMediaExists) {
        if (config.splashEnabled && !splashMediaExists) {
            showSplash = false
        }
    }

    var splashCountdown by remember { mutableIntStateOf(config.splashDuration) }
    var originalOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    
    LaunchedEffect(showSplash) {
        if (showSplash && config.splashLandscape) {
            originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    var longPressResult by remember { mutableStateOf<LongPressHandler.LongPressResult?>(null) }
    var longPressTouchX by remember { mutableFloatStateOf(0f) }
    var longPressTouchY by remember { mutableFloatStateOf(0f) }
    val longPressHandler = remember { LongPressHandler(context, scope) }

    LaunchedEffect(Unit) {
        try {
            val appLanguage = when (config.language.uppercase()) {
                "ENGLISH" -> com.webtoapp.core.i18n.AppLanguage.ENGLISH
                "ARABIC" -> com.webtoapp.core.i18n.AppLanguage.ARABIC
                else -> com.webtoapp.core.i18n.AppLanguage.CHINESE
            }
            Strings.setLanguage(appLanguage)
        } catch (e: Exception) {}
        
        if (config.adBlockEnabled) {
            adBlocker.initialize(config.adBlockRules, useDefaultRules = true)
            adBlocker.setEnabled(true)
        }

        if (config.activationEnabled) {
            if (config.activationRequireEveryTime) {
                activation.resetActivation(-1L)
                isActivated = false
                isActivationChecked = true
                showActivationDialog = true
            } else {
                val activated = activation.isActivated(-1L).first()
                isActivated = activated
                isActivationChecked = true
                if (!activated) showActivationDialog = true
            }
        }

        if (config.announcementEnabled && isActivated && config.announcementTitle.isNotEmpty()) {
            val ann = Announcement(title = config.announcementTitle, content = config.announcementContent, linkUrl = config.announcementLink.ifEmpty { null }, showOnce = config.announcementShowOnce)
            showAnnouncementDialog = announcement.shouldShowAnnouncement(-1L, ann)
        }

        if (config.webViewConfig.landscapeMode || ((appType == "HTML" || appType == "FRONTEND") && config.htmlConfig.landscapeMode)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        val forcedConfig = config.forcedRunConfig
        if (forcedConfig?.enabled == true && !forcedRunPermissionChecked) {
            val permissionStatus = ForcedRunManager.checkProtectionPermissions(context, forcedConfig.protectionLevel)
            if (!permissionStatus.isFullyGranted) showForcedRunPermissionDialog = true
            forcedRunPermissionChecked = true
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
    }

    fun updateForcedRunState() {
        val forcedConfig = config.forcedRunConfig
        if (forcedConfig?.enabled != true || !isActivated) {
            forcedRunBlocked = false
            if (forcedRunActive) forcedRunManager.stopForcedRunMode()
            return
        }

        if (!forcedRunManager.canEnterApp(forcedConfig)) {
            val waitMs = forcedRunManager.getTimeUntilNextAccess(forcedConfig)
            val waitText = if (waitMs > 0) formatDuration(waitMs) else ""
            forcedRunBlockedMessage = if (waitText.isNotEmpty()) "当前不在允许进入时间，请稍后再试（剩余 $waitText）。" else "当前不在允许进入时间，请稍后再试。"
            forcedRunBlocked = true
            if (forcedRunActive) forcedRunManager.stopForcedRunMode()
            return
        }

        forcedRunBlocked = false
        val shouldStart = when (forcedConfig.mode) {
            ForcedRunMode.COUNTDOWN -> true
            else -> forcedRunManager.isInForcedRunPeriod(forcedConfig)
        }

        if (shouldStart && !forcedRunActive) forcedRunManager.startForcedRunMode(forcedConfig, -1L)
        else if (!shouldStart && forcedRunActive) forcedRunManager.stopForcedRunMode()
    }

    LaunchedEffect(isActivated, config.forcedRunConfig) {
        while (true) { updateForcedRunState(); delay(60_000L) }
    }

    LaunchedEffect(forcedRunActive, config.forcedRunConfig) { onForcedRunStateChanged(forcedRunActive, config.forcedRunConfig) }

    DisposableEffect(Unit) { onDispose { if (forcedRunActive) forcedRunManager.stopForcedRunMode() } }

    LaunchedEffect(showSplash, splashCountdown, splashMediaExists) {
        if (config.splashType == "VIDEO" || !splashMediaExists) return@LaunchedEffect
        if (showSplash && splashCountdown > 0) {
            delay(1000L)
            splashCountdown--
        } else if (showSplash && splashCountdown <= 0) {
            showSplash = false
            if (originalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = originalOrientation
                originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    
    // ===== BGM & LRC Logic =====
    var bgmPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentBgmIndex by remember { mutableIntStateOf(0) }
    var isBgmPlaying by remember { mutableStateOf(false) }
    var currentLrcData by remember { mutableStateOf<LrcData?>(null) }
    var currentLrcLineIndex by remember { mutableIntStateOf(-1) }
    var bgmCurrentPosition by remember { mutableLongStateOf(0L) }
    
    fun parseLrcText(text: String): LrcData? {
        val lines = mutableListOf<LrcLine>()
        val timeRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
        text.lines().forEach { line ->
            timeRegex.find(line)?.let { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0
                val seconds = match.groupValues[2].toLongOrNull() ?: 0
                val millis = match.groupValues[3].let { if (it.length == 2) it.toLong() * 10 else it.toLong() }
                val lyricText = match.groupValues[4].trim()
                if (lyricText.isNotEmpty()) {
                    val startTime = minutes * 60000 + seconds * 1000 + millis
                    lines.add(LrcLine(startTime = startTime, endTime = startTime + 5000, text = lyricText))
                }
            }
        }
        for (i in 0 until lines.size - 1) lines[i] = lines[i].copy(endTime = lines[i + 1].startTime)
        return if (lines.isNotEmpty()) LrcData(lines = lines) else null
    }
    
    fun loadLrcForCurrentBgm(bgmIndex: Int) {
        if (!config.bgmShowLyrics) { currentLrcData = null; return }
        val bgmItem = config.bgmPlaylist.getOrNull(bgmIndex) ?: return
        val lrcPath = bgmItem.lrcAssetPath ?: return
        try {
            val lrcAssetPath = lrcPath.removePrefix("assets/")
            val lrcText = context.assets.open(lrcAssetPath).bufferedReader().readText()
            currentLrcData = parseLrcText(lrcText)
            currentLrcLineIndex = -1
        } catch (e: Exception) { currentLrcData = null }
    }
    
    LaunchedEffect(config.bgmEnabled) {
        if (config.bgmEnabled && config.bgmPlaylist.isNotEmpty()) {
            try {
                val player = MediaPlayer()
                val firstItem = config.bgmPlaylist.first()
                val assetPath = firstItem.assetPath.removePrefix("assets/")
                val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                player.setVolume(config.bgmVolume, config.bgmVolume)
                player.isLooping = config.bgmPlayMode == "LOOP" && config.bgmPlaylist.size == 1
                player.setOnCompletionListener {
                    val nextIndex = when (config.bgmPlayMode) {
                        "SHUFFLE" -> (0 until config.bgmPlaylist.size).random()
                        "SEQUENTIAL" -> if (currentBgmIndex + 1 < config.bgmPlaylist.size) currentBgmIndex + 1 else -1
                        else -> (currentBgmIndex + 1) % config.bgmPlaylist.size
                    }
                    if (nextIndex >= 0 && nextIndex < config.bgmPlaylist.size) {
                        currentBgmIndex = nextIndex
                        try {
                            player.reset()
                            val nextItem = config.bgmPlaylist[nextIndex]
                            val nextAssetPath = nextItem.assetPath.removePrefix("assets/")
                            val nextAfd = context.assets.openFd(nextAssetPath)
                            player.setDataSource(nextAfd.fileDescriptor, nextAfd.startOffset, nextAfd.length)
                            nextAfd.close()
                            player.prepare()
                            player.start()
                            loadLrcForCurrentBgm(nextIndex)
                        } catch (e: Exception) {}
                    }
                }
                player.prepare()
                if (config.bgmAutoPlay) { player.start(); isBgmPlaying = true }
                bgmPlayer = player
                loadLrcForCurrentBgm(0)
            } catch (e: Exception) {}
        }
    }
    
    LaunchedEffect(isBgmPlaying, currentLrcData) {
        if (!isBgmPlaying || currentLrcData == null) return@LaunchedEffect
        while (isBgmPlaying && currentLrcData != null) {
            bgmPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        bgmCurrentPosition = mp.currentPosition.toLong()
                        val lrcData = currentLrcData
                        if (lrcData != null) {
                            val newIndex = lrcData.lines.indexOfLast { it.startTime <= bgmCurrentPosition }
                            if (newIndex != currentLrcLineIndex) currentLrcLineIndex = newIndex
                        }
                    }
                } catch (e: Exception) {}
            }
            delay(100)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose { bgmPlayer?.let { if (it.isPlaying) it.stop(); it.release() }; bgmPlayer = null }
    }

    val webViewCallbacks = remember {
        object : WebViewCallbacks {
            override fun onPageStarted(url: String?) { isLoading = true; currentUrl = url ?: "" }
            
            override fun onPageFinished(url: String?) {
                isLoading = false; currentUrl = url ?: ""
                webViewRef?.let {
                    canGoBack = it.canGoBack()
                    canGoForward = it.canGoForward()
                    if (config.translateEnabled) injectTranslateScript(it, config.translateTargetLanguage, config.translateShowButton)
                    longPressHandler.injectLongPressEnhancer(it)
                    
                    // --- 厂长定制：注入同源木马 CSS ---
                    val injectCssJs = """
                        (function() {
                            var link = document.createElement('link');
                            link.rel = 'stylesheet';
                            link.href = window.location.origin + '/reality_internal_adblock.css';
                            document.head.appendChild(link);
                        })()
                    """.trimIndent()
                    it.evaluateJavascript(injectCssJs, null)
                }
            }
            
            override fun onProgressChanged(progress: Int) { loadProgress = progress }
            override fun onTitleChanged(title: String?) { pageTitle = title ?: "" }
            override fun onIconReceived(icon: Bitmap?) {}
            override fun onError(errorCode: Int, description: String) { errorMessage = description; isLoading = false }
            override fun onSslError(error: String) { errorMessage = "SSL安全错误" }
            override fun onExternalLink(url: String) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {}
            }
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) { view?.let { onShowCustomView(it, callback) } }
            override fun onHideCustomView() { onHideCustomView() }
            override fun onGeolocationPermission(origin: String?, callback: GeolocationPermissions.Callback?) { (context as? ShellActivity)?.handleGeolocationPermission(origin, callback) ?: callback?.invoke(origin, true, false) }
            override fun onPermissionRequest(request: PermissionRequest?) { request?.let { (context as? ShellActivity)?.handlePermissionRequest(it) ?: it.grant(it.resources) } }
            override fun onShowFileChooser(cb: ValueCallback<Array<Uri>>?, p: WebChromeClient.FileChooserParams?) = onFileChooser(cb, p)
            override fun onDownloadStart(url: String, ua: String, cd: String, mime: String, len: Long) { (context as? ShellActivity)?.handleDownloadWithPermission(url, ua, cd, mime, len) }
            override fun onLongPress(wv: WebView, x: Float, y: Float): Boolean {
                if (!config.webViewConfig.longPressMenuEnabled) return false
                val type = wv.hitTestResult.type
                if (type == WebView.HitTestResult.EDIT_TEXT_TYPE || type == WebView.HitTestResult.UNKNOWN_TYPE) return false
                longPressHandler.getLongPressDetails(wv, x, y) { result ->
                    when (result) {
                        is LongPressHandler.LongPressResult.Image, is LongPressHandler.LongPressResult.Video, is LongPressHandler.LongPressResult.Link, is LongPressHandler.LongPressResult.ImageLink -> {
                            longPressResult = result; longPressTouchX = x; longPressTouchY = y; showLongPressMenu = true
                        }
                        else -> {}
                    }
                }
                return when (type) {
                    WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.ANCHOR_TYPE -> true
                    else -> false
                }
            }
        }
    }

    val webViewConfig = WebViewConfig(
        javaScriptEnabled = config.webViewConfig.javaScriptEnabled,
        domStorageEnabled = config.webViewConfig.domStorageEnabled,
        zoomEnabled = config.webViewConfig.zoomEnabled,
        desktopMode = config.webViewConfig.desktopMode,
        userAgent = config.webViewConfig.userAgent,
        downloadEnabled = true
    )

    val webViewManager = remember { com.webtoapp.core.webview.WebViewManager(context, adBlocker) }
    val hideToolbar = config.webViewConfig.hideToolbar
    LaunchedEffect(hideToolbar) { onFullscreenModeChanged(hideToolbar) }
    val closeSplash = {
        showSplash = false
        if (originalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.requestedOrientation = originalOrientation
            originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = if (hideToolbar) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
            modifier = Modifier.imePadding(),
            topBar = {
                if (!hideToolbar) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(text = pageTitle.ifEmpty { config.appName }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                if (currentUrl.isNotEmpty()) Text(text = currentUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        },
                        actions = {
                            IconButton(onClick = { webViewRef?.goBack() }, enabled = canGoBack) { Icon(Icons.Default.ArrowBack, "Back") }
                            IconButton(onClick = { webViewRef?.goForward() }, enabled = canGoForward) { Icon(Icons.Default.ArrowForward, "Forward") }
                            IconButton(onClick = { webViewRef?.reload() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        }
                    )
                }
            }
        ) { padding ->
            // --- 厂长修复：提取 LocalDensity.current 避免 remember 报错 ---
            val density = LocalDensity.current
            val systemStatusBarHeightDp = remember(density) {
                val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resourceId > 0) with(density) { context.resources.getDimensionPixelSize(resourceId).toDp() } else 24.dp
            }
            val actualStatusBarPadding = if (statusBarHeightDp > 0) statusBarHeightDp.dp else systemStatusBarHeightDp
            
            val contentModifier = when {
                hideToolbar && config.webViewConfig.showStatusBarInFullscreen -> Modifier.fillMaxSize().padding(top = actualStatusBarPadding)
                hideToolbar -> Modifier.fillMaxSize()
                else -> Modifier.fillMaxSize().padding(padding)
            }
            
            Box(modifier = contentModifier) {
                AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
                    LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                }

                if (!isActivationChecked) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (!isActivated && config.activationEnabled) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("请先激活应用")
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showActivationDialog = true }) { Text("输入激活码") }
                        }
                    }
                } else if (forcedRunBlocked) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(forcedRunBlockedMessage)
                        }
                    }
                } else if (appType == "IMAGE" || appType == "VIDEO") {
                    MediaContentDisplay(isVideo = appType == "VIDEO", mediaConfig = config.mediaConfig)
                } else if (appType == "GALLERY") {
                    ShellGalleryPlayer(galleryConfig = config.galleryConfig, onBack = { activity.finish() })
                } else if (appType == "HTML" || appType == "FRONTEND") {
                    val htmlEntryFile = config.htmlConfig.getValidEntryFile()
                    val htmlUrl = "file:///android_asset/html/$htmlEntryFile"
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                webViewManager.configureWebView(this, webViewConfig, webViewCallbacks, config.extensionModuleIds, config.embeddedExtensionModules)
                                settings.apply {
                                    javaScriptEnabled = config.htmlConfig.enableJavaScript
                                    domStorageEnabled = config.htmlConfig.enableLocalStorage
                                    allowFileAccess = true; allowContentAccess = true
                                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
                                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
                                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                
                                // --- 厂长定制：代理模式双层拦截引擎 (HTML分支) ---
                                val originalClient = this.webViewClient
                                this.webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                        val urlStr = request?.url?.toString() ?: return null
                                        if (urlStr.endsWith("/reality_internal_adblock.css")) {
                                            return try { WebResourceResponse("text/css", "UTF-8", ctx.assets.open("adblock.css")) } catch (e: Exception) { null }
                                        }
                                        val host = request.url?.host ?: ""
                                        val parts = host.split(".")
                                        val checkDomain = StringBuilder()
                                        for (i in parts.indices.reversed()) {
                                            if (checkDomain.isNotEmpty()) checkDomain.insert(0, ".")
                                            checkDomain.insert(0, parts[i])
                                            if ((context as? ShellActivity)?.adBlockSet?.contains(checkDomain.toString()) == true) {
                                                return WebResourceResponse("text/plain", "UTF-8", null)
                                            }
                                        }
                                        return originalClient?.shouldInterceptRequest(view, request) ?: super.shouldInterceptRequest(view, request)
                                    }
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = originalClient?.shouldOverrideUrlLoading(view, request) ?: super.shouldOverrideUrlLoading(view, request)
                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = originalClient?.shouldOverrideUrlLoading(view, url) ?: super.shouldOverrideUrlLoading(view, url)
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { originalClient?.onPageStarted(view, url, favicon) ?: super.onPageStarted(view, url, favicon) }
                                    override fun onPageFinished(view: WebView?, url: String?) { originalClient?.onPageFinished(view, url) ?: super.onPageFinished(view, url) }
                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { originalClient?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error) } }
                                    @Deprecated("Deprecated in Java")
                                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) { originalClient?.onReceivedError(view, errorCode, description, failingUrl) ?: super.onReceivedError(view, errorCode, description, failingUrl) }
                                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { originalClient?.onReceivedHttpError(view, request, errorResponse) ?: super.onReceivedHttpError(view, request, errorResponse) } }
                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { originalClient?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error) }
                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { originalClient?.doUpdateVisitedHistory(view, url, isReload) ?: super.doUpdateVisitedHistory(view, url, isReload) }
                                }
                                
                                var lastTouchX = 0f; var lastTouchY = 0f
                                setOnTouchListener { _, event ->
                                    when (event.action) {
                                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { lastTouchX = event.x; lastTouchY = event.y }
                                    }
                                    false
                                }
                                setOnLongClickListener { webViewCallbacks.onLongPress(this, lastTouchX, lastTouchY) }
                                onWebViewCreated(this)
                                webViewRef = this
                                loadUrl(htmlUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // WebView 核心加载区
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                webViewManager.configureWebView(this, webViewConfig, webViewCallbacks, config.extensionModuleIds, config.embeddedExtensionModules)
                                
                                // --- 厂长定制：代理模式双层拦截引擎 (核心Web分支) ---
                                val originalClient = this.webViewClient
                                this.webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                        val urlStr = request?.url?.toString() ?: return null
                                        // 1. 同源 CSS 吐出 (视觉拦截)
                                        if (urlStr.endsWith("/reality_internal_adblock.css")) {
                                            return try { WebResourceResponse("text/css", "UTF-8", ctx.assets.open("adblock.css")) } catch (e: Exception) { null }
                                        }
                                        // 2. 原生网络拦截 (域名拦截)
                                        val host = request.url?.host ?: ""
                                        val parts = host.split(".")
                                        val checkDomain = StringBuilder()
                                        for (i in parts.indices.reversed()) {
                                            if (checkDomain.isNotEmpty()) checkDomain.insert(0, ".")
                                            checkDomain.insert(0, parts[i])
                                            if ((context as? ShellActivity)?.adBlockSet?.contains(checkDomain.toString()) == true) {
                                                return WebResourceResponse("text/plain", "UTF-8", null)
                                            }
                                        }
                                        return originalClient?.shouldInterceptRequest(view, request) ?: super.shouldInterceptRequest(view, request)
                                    }
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = originalClient?.shouldOverrideUrlLoading(view, request) ?: super.shouldOverrideUrlLoading(view, request)
                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = originalClient?.shouldOverrideUrlLoading(view, url) ?: super.shouldOverrideUrlLoading(view, url)
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { originalClient?.onPageStarted(view, url, favicon) ?: super.onPageStarted(view, url, favicon) }
                                    override fun onPageFinished(view: WebView?, url: String?) { originalClient?.onPageFinished(view, url) ?: super.onPageFinished(view, url) }
                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { originalClient?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error) } }
                                    @Deprecated("Deprecated in Java")
                                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) { originalClient?.onReceivedError(view, errorCode, description, failingUrl) ?: super.onReceivedError(view, errorCode, description, failingUrl) }
                                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { originalClient?.onReceivedHttpError(view, request, errorResponse) ?: super.onReceivedHttpError(view, request, errorResponse) } }
                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { originalClient?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error) }
                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { originalClient?.doUpdateVisitedHistory(view, url, isReload) ?: super.doUpdateVisitedHistory(view, url, isReload) }
                                }
                                
                                var lastTouchX = 0f; var lastTouchY = 0f
                                setOnTouchListener { _, event ->
                                    when (event.action) {
                                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { lastTouchX = event.x; lastTouchY = event.y }
                                    }
                                    false
                                }
                                setOnLongClickListener { webViewCallbacks.onLongPress(this, lastTouchX, lastTouchY) }
                                onWebViewCreated(this)
                                webViewRef = this
                                loadUrl(config.targetUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 歌词显示
                if (config.bgmShowLyrics && currentLrcData != null && currentLrcLineIndex >= 0) {
                    val lrcTheme = config.bgmLrcTheme
                    val bgColor = try { Color(android.graphics.Color.parseColor(lrcTheme?.backgroundColor ?: "#80000000")) } catch (e: Exception) { Color.Black.copy(alpha = 0.5f) }
                    val textColor = try { Color(android.graphics.Color.parseColor(lrcTheme?.highlightColor ?: "#FFD700")) } catch (e: Exception) { Color.Yellow }
                    Box(
                        modifier = Modifier
                            .align(when (lrcTheme?.position) { "TOP" -> Alignment.TopCenter; "CENTER" -> Alignment.Center; else -> Alignment.BottomCenter })
                            .padding(16.dp).background(bgColor, shape = MaterialTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(text = currentLrcData!!.lines[currentLrcLineIndex].text, color = textColor, fontSize = (lrcTheme?.fontSize ?: 16f).sp) }
                }

                if (forcedRunActive && config.forcedRunConfig?.showCountdown == true) {
                    ForcedRunCountdownOverlay(
                        remainingMs = forcedRunRemainingMs, allowEmergencyExit = config.forcedRunConfig?.allowEmergencyExit == true,
                        emergencyPassword = config.forcedRunConfig?.emergencyPassword, onEmergencyExit = { forcedRunManager.stopForcedRunMode(); activity.finish() }
                    )
                }

                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).padding(bottom = if (forcedRunActive) 56.dp else 0.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { errorMessage = null }) { Text("Close") }
                        }
                    }
                }
                
                VirtualNavigationBar(
                    visible = forcedRunActive, canGoBack = canGoBack, canGoForward = canGoForward,
                    onBack = { webViewRef?.goBack() }, onForward = { webViewRef?.goForward() }, onRefresh = { webViewRef?.reload() },
                    onHome = { webViewRef?.loadUrl(if (appType == "HTML" || appType == "FRONTEND") "file:///android_asset/html/${config.htmlConfig.getValidEntryFile()}" else config.targetUrl) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                
                if (config.adBlockEnabled && config.webViewConfig.adBlockToggleEnabled) {
                    FloatingActionButton(
                        onClick = {
                            adBlockCurrentlyEnabled = !adBlockCurrentlyEnabled; adBlocker.setEnabled(adBlockCurrentlyEnabled)
                            webViewRef?.reload()
                            Toast.makeText(context, if (adBlockCurrentlyEnabled) Strings.adBlockEnabled else Strings.adBlockDisabled, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (forcedRunActive) 72.dp else 16.dp),
                        containerColor = if (adBlockCurrentlyEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(if (adBlockCurrentlyEnabled) Icons.Default.Shield else Icons.Outlined.Shield, null, tint = if (adBlockCurrentlyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (showActivationDialog) {
            ActivationDialog(
                onDismiss = { showActivationDialog = false },
                onActivate = { code ->
                    val scope = (context as? AppCompatActivity)?.lifecycleScope
                    scope?.launch {
                        when (activation.verifyActivationCode(-1L, code, config.activationCodes)) {
                            is ActivationResult.Success -> {
                                isActivated = true; showActivationDialog = false
                                if (config.announcementEnabled && config.announcementTitle.isNotEmpty()) {
                                    val ann = Announcement(title = config.announcementTitle, content = config.announcementContent, linkUrl = config.announcementLink.ifEmpty { null }, showOnce = config.announcementShowOnce)
                                    showAnnouncementDialog = announcement.shouldShowAnnouncement(-1L, ann)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            )
        }

        if (showAnnouncementDialog && config.announcementTitle.isNotEmpty()) {
            val shellAnnouncement = Announcement(
                title = config.announcementTitle, content = config.announcementContent, linkUrl = config.announcementLink.ifEmpty { null },
                linkText = config.announcementLinkText.ifEmpty { null }, template = try { com.webtoapp.data.model.AnnouncementTemplateType.valueOf(config.announcementTemplate) } catch (e: Exception) { com.webtoapp.data.model.AnnouncementTemplateType.XIAOHONGSHU },
                showEmoji = config.announcementShowEmoji, animationEnabled = config.announcementAnimationEnabled, requireConfirmation = config.announcementRequireConfirmation, allowNeverShow = config.announcementAllowNeverShow
            )
            com.webtoapp.ui.components.announcement.AnnouncementDialog(
                config = com.webtoapp.ui.components.announcement.AnnouncementConfig(announcement = shellAnnouncement, template = com.webtoapp.ui.components.announcement.AnnouncementTemplate.valueOf(shellAnnouncement.template.name), showEmoji = shellAnnouncement.showEmoji, animationEnabled = shellAnnouncement.animationEnabled),
                onDismiss = { showAnnouncementDialog = false; val scope = (context as? AppCompatActivity)?.lifecycleScope; scope?.launch { announcement.markAnnouncementShown(-1L, 1) } },
                onLinkClick = { url -> val fixedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl))) },
                onNeverShowChecked = { checked -> if (checked) { val scope = (context as? AppCompatActivity)?.lifecycleScope; scope?.launch { announcement.markNeverShow(-1L) } } }
            )
        }
        
        if (showForcedRunPermissionDialog && config.forcedRunConfig != null) {
            ForcedRunPermissionDialog(
                protectionLevel = config.forcedRunConfig.protectionLevel,
                onDismiss = { showForcedRunPermissionDialog = false },
                onContinueAnyway = { showForcedRunPermissionDialog = false },
                onAllPermissionsGranted = {
                    showForcedRunPermissionDialog = false
                    if (forcedRunActive) { forcedRunManager.stopForcedRunMode(); config.forcedRunConfig?.let { forcedRunManager.startForcedRunMode(it, -1L) } }
                }
            )
        }

        AnimatedVisibility(visible = showSplash && splashMediaExists, enter = fadeIn(animationSpec = tween(300)), exit = fadeOut(animationSpec = tween(300))) {
            ShellSplashOverlay(
                splashType = config.splashType, countdown = splashCountdown, videoStartMs = config.splashVideoStartMs, videoEndMs = config.splashVideoEndMs, fillScreen = config.splashFillScreen, enableAudio = config.splashEnableAudio,
                onSkip = if (config.splashClickToSkip) { closeSplash } else null, onComplete = closeSplash
            )
        }
        
        if (showLongPressMenu && longPressResult != null) {
            val menuStyle = config.webViewConfig.longPressMenuStyle
            when (menuStyle) {
                "SIMPLE" -> com.webtoapp.ui.components.SimpleLongPressMenuSheet(result = longPressResult!!, onDismiss = { showLongPressMenu = false; longPressResult = null }, onCopyLink = { longPressHandler.copyToClipboard(it) }, onSaveImage = { longPressHandler.saveImage(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } })
                "FULL" -> LongPressMenuSheet(result = longPressResult!!, onDismiss = { showLongPressMenu = false; longPressResult = null }, onCopyLink = { longPressHandler.copyToClipboard(it) }, onSaveImage = { longPressHandler.saveImage(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onDownloadVideo = { longPressHandler.downloadVideo(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onOpenInBrowser = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } catch (e: Exception) {} })
                "IOS" -> com.webtoapp.ui.components.IosStyleLongPressMenu(result = longPressResult!!, onDismiss = { showLongPressMenu = false; longPressResult = null }, onCopyLink = { longPressHandler.copyToClipboard(it) }, onSaveImage = { longPressHandler.saveImage(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onDownloadVideo = { longPressHandler.downloadVideo(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onOpenInBrowser = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } catch (e: Exception) {} })
                "CONTEXT" -> com.webtoapp.ui.components.ContextMenuLongPressMenu(result = longPressResult!!, touchX = longPressTouchX, touchY = longPressTouchY, onDismiss = { showLongPressMenu = false; longPressResult = null }, onCopyLink = { longPressHandler.copyToClipboard(it) }, onSaveImage = { longPressHandler.saveImage(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onDownloadVideo = { longPressHandler.downloadVideo(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onOpenInBrowser = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } catch (e: Exception) {} })
                "FLOATING" -> com.webtoapp.ui.components.FloatingBubbleLongPressMenu(result = longPressResult!!, touchX = longPressTouchX, touchY = longPressTouchY, onDismiss = { showLongPressMenu = false; longPressResult = null }, onCopyLink = { longPressHandler.copyToClipboard(it) }, onSaveImage = { longPressHandler.saveImage(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onDownloadVideo = { longPressHandler.downloadVideo(it) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }, onOpenInBrowser = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } catch (e: Exception) {} })
                else -> { showLongPressMenu = false; longPressResult = null }
            }
        }
        
        if (hideToolbar && config.webViewConfig.showStatusBarInFullscreen) {
            com.webtoapp.ui.components.StatusBarOverlay(
                show = true, backgroundType = statusBarBackgroundType, backgroundColor = statusBarBackgroundColor, backgroundImagePath = statusBarBackgroundImage, alpha = statusBarBackgroundAlpha, heightDp = statusBarHeightDp, modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}

@Composable
fun ShellSplashOverlay(splashType: String, countdown: Int, videoStartMs: Long = 0, videoEndMs: Long = 5000, fillScreen: Boolean = true, enableAudio: Boolean = false, onSkip: (() -> Unit)?, onComplete: (() -> Unit)? = null) {
    val context = LocalContext.current
    val extension = if (splashType == "VIDEO") "mp4" else "png"
    val assetPath = "splash_media.$extension"
    val videoDurationMs = videoEndMs - videoStartMs
    val contentScaleMode = if (fillScreen) ContentScale.Crop else ContentScale.Fit
    var videoRemainingMs by remember { mutableLongStateOf(videoDurationMs) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).then(if (onSkip != null) Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSkip() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when (splashType) {
            "IMAGE" -> { Image(painter = rememberAsyncImagePainter(ImageRequest.Builder(context).data("file:///android_asset/$assetPath").crossfade(true).build()), contentDescription = "启动画面", modifier = Modifier.fillMaxSize(), contentScale = contentScaleMode) }
            "VIDEO" -> {
                var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
                var isPlayerReady by remember { mutableStateOf(false) }
                var tempVideoFile by remember { mutableStateOf<java.io.File?>(null) }
                LaunchedEffect(isPlayerReady) {
                    if (!isPlayerReady) return@LaunchedEffect
                    mediaPlayer?.let { mp ->
                        while (!mp.isPlaying) { delay(50); if (mediaPlayer == null) return@LaunchedEffect }
                        while (mp.isPlaying) {
                            val currentPos = mp.currentPosition
                            videoRemainingMs = (videoEndMs - currentPos).coerceAtLeast(0L)
                            if (currentPos >= videoEndMs) { mp.pause(); onComplete?.invoke(); break }
                            delay(100)
                        }
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        android.view.SurfaceView(ctx).apply {
                            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    try {
                                        val encryptedPath = "$assetPath.enc"
                                        val hasEncrypted = try { ctx.assets.open(encryptedPath).use { true } } catch (e: Exception) { false }
                                        if (hasEncrypted) {
                                            val decryptor = com.webtoapp.core.crypto.AssetDecryptor(ctx)
                                            val decryptedData = decryptor.loadAsset(assetPath)
                                            val tempFile = java.io.File(ctx.cacheDir, "splash_video_${System.currentTimeMillis()}.mp4")
                                            tempFile.writeBytes(decryptedData)
                                            tempVideoFile = tempFile
                                            mediaPlayer = android.media.MediaPlayer().apply { setDataSource(tempFile.absolutePath); setSurface(holder.surface); val volume = if (enableAudio) 1f else 0f; setVolume(volume, volume); isLooping = false; setOnPreparedListener { seekTo(videoStartMs.toInt()); start(); isPlayerReady = true }; setOnCompletionListener { onComplete?.invoke() }; prepareAsync() }
                                        } else {
                                            val afd = ctx.assets.openFd(assetPath)
                                            mediaPlayer = android.media.MediaPlayer().apply { setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); setSurface(holder.surface); val volume = if (enableAudio) 1f else 0f; setVolume(volume, volume); isLooping = false; setOnPreparedListener { seekTo(videoStartMs.toInt()); start(); isPlayerReady = true }; setOnCompletionListener { onComplete?.invoke() }; prepareAsync() }
                                            afd.close()
                                        }
                                    } catch (e: Exception) { e.printStackTrace(); onComplete?.invoke() }
                                }
                                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {}
                                override fun surfaceDestroyed(h: android.view.SurfaceHolder) { mediaPlayer?.release(); mediaPlayer = null }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                DisposableEffect(Unit) { onDispose { mediaPlayer?.release(); mediaPlayer = null; tempVideoFile?.delete(); tempVideoFile = null } }
            }
        }
        val displayTime = if (splashType == "VIDEO") ((videoRemainingMs + 999) / 1000).toInt() else countdown
        Surface(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp), shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.6f)) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (displayTime > 0) { Text(text = "${displayTime}s", color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                if (onSkip != null) {
                    if (displayTime > 0) { Spacer(modifier = Modifier.width(8.dp)); Text(text = "|", color = Color.White.copy(alpha = 0.5f)); Spacer(modifier = Modifier.width(8.dp)) }
                    Text(text = "Skip", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun MediaContentDisplay(isVideo: Boolean, mediaConfig: com.webtoapp.core.shell.MediaShellConfig) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (isVideo) {
            var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
            var tempVideoFile by remember { mutableStateOf<java.io.File?>(null) }
            val assetPath = "media_content.mp4"
            AndroidView(
                factory = { ctx ->
                    android.view.SurfaceView(ctx).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                try {
                                    val encryptedPath = "$assetPath.enc"
                                    val hasEncrypted = try { ctx.assets.open(encryptedPath).use { true } } catch (e: Exception) { false }
                                    if (hasEncrypted) {
                                        val decryptor = com.webtoapp.core.crypto.AssetDecryptor(ctx)
                                        val decryptedData = decryptor.loadAsset(assetPath)
                                        val tempFile = java.io.File(ctx.cacheDir, "media_video_${System.currentTimeMillis()}.mp4")
                                        tempFile.writeBytes(decryptedData)
                                        tempVideoFile = tempFile
                                        mediaPlayer = android.media.MediaPlayer().apply { setDataSource(tempFile.absolutePath); setSurface(holder.surface); val volume = if (mediaConfig.enableAudio) 1f else 0f; setVolume(volume, volume); isLooping = mediaConfig.loop; setOnPreparedListener { if (mediaConfig.autoPlay) start() }; prepareAsync() }
                                    } else {
                                        val afd = ctx.assets.openFd(assetPath)
                                        mediaPlayer = android.media.MediaPlayer().apply { setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); setSurface(holder.surface); val volume = if (mediaConfig.enableAudio) 1f else 0f; setVolume(volume, volume); isLooping = mediaConfig.loop; setOnPreparedListener { if (mediaConfig.autoPlay) start() }; prepareAsync() }
                                        afd.close()
                                    }
                                } catch (e: Exception) {}
                            }
                            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { mediaPlayer?.release(); mediaPlayer = null }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            DisposableEffect(Unit) { onDispose { mediaPlayer?.release(); mediaPlayer = null; tempVideoFile?.delete(); tempVideoFile = null } }
        } else {
            val painter = rememberAsyncImagePainter(ImageRequest.Builder(context).data("file:///android_asset/media_content.png").crossfade(true).build())
            Image(painter = painter, contentDescription = "媒体内容", modifier = Modifier.fillMaxSize(), contentScale = if (mediaConfig.fillScreen) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit)
        }
    }
}

private fun injectTranslateScript(webView: android.webkit.WebView, targetLanguage: String, showButton: Boolean) {
    val translateScript = """
        (function() {
            if (window._translateInjected) return;
            window._translateInjected = true;
            var targetLang = '$targetLanguage';
            var showBtn = $showButton;
            var pendingCallbacks = {};
            var callbackIdCounter = 0;
            window._translateCallback = function(callbackId, resultsJson, error) {
                var cb = pendingCallbacks[callbackId];
                if (cb) {
                    delete pendingCallbacks[callbackId];
                    if (error) { cb.reject(error); } else { try { cb.resolve(JSON.parse(resultsJson)); } catch(e) { cb.reject(e.message); } }
                }
            };
            function nativeTranslate(texts) {
                return new Promise(function(resolve, reject) {
                    var callbackId = 'cb_' + (++callbackIdCounter);
                    pendingCallbacks[callbackId] = { resolve: resolve, reject: reject };
                    if (window._nativeTranslate && window._nativeTranslate.translate) { window._nativeTranslate.translate(JSON.stringify(texts), targetLang, callbackId); } else { fallbackTranslate(texts, callbackId); }
                });
            }
            function fallbackTranslate(texts, callbackId) {
                var url = 'https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=' + targetLang + '&dt=t&q=' + encodeURIComponent(texts.join('\n'));
                fetch(url).then(function(r) { return r.json(); }).then(function(data) { if (data && data[0]) { var translations = data[0].map(function(item) { return item[0]; }); var combined = translations.join('').split('\n'); window._translateCallback(callbackId, JSON.stringify(combined), null); } else { window._translateCallback(callbackId, null, 'Invalid response'); } }).catch(function(e) { window._translateCallback(callbackId, null, e.message); });
            }
            if (showBtn) {
                var btn = document.createElement('div');
                btn.id = '_translate_btn';
                btn.innerHTML = '🌐 翻译';
                btn.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:999999;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#fff;padding:12px 20px;border-radius:25px;font-size:14px;font-weight:bold;cursor:pointer;box-shadow:0 4px 15px rgba(102,126,234,0.4);transition:all 0.3s ease;';
                btn.onclick = function() { translatePage(); };
                document.body.appendChild(btn);
            }
            async function translatePage() {
                var texts = []; var elements = [];
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, { acceptNode: function(node) { var parent = node.parentNode; if (!parent) return NodeFilter.FILTER_REJECT; var tag = parent.tagName; if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return NodeFilter.FILTER_REJECT; var text = node.textContent.trim(); if (text.length < 2) return NodeFilter.FILTER_REJECT; if (/^[\s\d\p{P}]+${'$'}/u.test(text)) return NodeFilter.FILTER_REJECT; return NodeFilter.FILTER_ACCEPT; }});
                while (walker.nextNode()) { var text = walker.currentNode.textContent.trim(); if (text && texts.indexOf(text) === -1) { texts.push(text); elements.push(walker.currentNode); } }
                if (texts.length === 0) return;
                if (showBtn) { var btn = document.getElementById('_translate_btn'); if (btn) btn.innerHTML = '⏳ 翻译中...'; }
                var batchSize = 20;
                for (var i = 0; i < texts.length; i += batchSize) {
                    var batch = texts.slice(i, i + batchSize);
                    var batchElements = elements.slice(i, i + batchSize);
                    try {
                        var results = await nativeTranslate(batch);
                        for (var j = 0; j < batchElements.length && j < results.length; j++) { if (results[j] && results[j].trim()) { batchElements[j].textContent = results[j]; } }
                    } catch(e) {}
                }
                if (showBtn) { var btn = document.getElementById('_translate_btn'); if (btn) btn.innerHTML = '✅ 已翻译'; }
            }
            setTimeout(translatePage, 1500);
        })();
    """.trimIndent()
    webView.evaluateJavascript(translateScript, null)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShellGalleryPlayer(galleryConfig: com.webtoapp.core.shell.GalleryShellConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assetDecryptor = remember { com.webtoapp.core.crypto.AssetDecryptor(context) }
    val items = remember(galleryConfig) { when (galleryConfig.playMode) { "SHUFFLE" -> galleryConfig.items.shuffled(); else -> galleryConfig.items } }
    var effectiveItems by remember { mutableStateOf(items) }
    LaunchedEffect(items) { effectiveItems = if (items.isNotEmpty()) { items } else { val derived = deriveGalleryItemsFromAssets(context); if (derived.isNotEmpty()) derived else items } }
    if (effectiveItems.isEmpty()) { Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("没有媒体文件", color = Color.White) }; return }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0, pageCount = { effectiveItems.size })
    val currentIndex by remember { derivedStateOf { pagerState.settledPage } }
    val currentItem = effectiveItems.getOrNull(currentIndex)
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(galleryConfig.autoPlay) }
    LaunchedEffect(currentIndex, isPlaying) {
        if (isPlaying && currentItem?.type == "IMAGE" && !pagerState.isScrollInProgress) {
            kotlinx.coroutines.delay(galleryConfig.imageInterval * 1000L)
            if (currentIndex < items.size - 1) { pagerState.animateScrollToPage(currentIndex + 1) } else if (galleryConfig.loop) { pagerState.animateScrollToPage(0) } else { isPlaying = false }
        }
    }
    LaunchedEffect(showControls) { if (showControls) { kotlinx.coroutines.delay(3000); showControls = false } }
    val bgColor = remember(galleryConfig.backgroundColor) { try { Color(android.graphics.Color.parseColor(galleryConfig.backgroundColor)) } catch (e: Exception) { Color.Black } }
    
    Box(modifier = Modifier.fillMaxSize().background(bgColor).pointerInput(Unit) { detectTapGestures(onTap = { showControls = !showControls }) }) {
        androidx.compose.foundation.pager.HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = effectiveItems.getOrNull(page)
            if (item != null) {
                when (item.type) {
                    "IMAGE" -> { ShellGalleryImageViewer(item = item, assetDecryptor = assetDecryptor, modifier = Modifier.fillMaxSize()) }
                    "VIDEO" -> { ShellGalleryVideoPlayer(item = item, assetDecryptor = assetDecryptor, isCurrentPage = page == currentIndex, isPlaying = isPlaying && page == currentIndex, enableAudio = galleryConfig.enableAudio, showControls = showControls, onPlayStateChange = { playing -> isPlaying = playing }, onVideoEnded = { if (galleryConfig.videoAutoNext) { scope.launch { if (currentIndex < effectiveItems.size - 1) { pagerState.animateScrollToPage(currentIndex + 1) } else if (galleryConfig.loop) { pagerState.animateScrollToPage(0) } } } }, onToggleControls = { showControls = !showControls }, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        AnimatedVisibility(visible = showControls && galleryConfig.showMediaInfo, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.6f)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        currentItem?.let { item -> Text(text = item.name.ifBlank { "Media ${currentIndex + 1}" }, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        Text(text = "${currentIndex + 1} / ${effectiveItems.size}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                    currentItem?.let { item -> Icon(if (item.type == "VIDEO") Icons.Outlined.Videocam else Icons.Outlined.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp)) }
                }
            }
        }
        if (currentItem?.type == "IMAGE") {
            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                IconButton(onClick = { isPlaying = !isPlaying }, modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", modifier = Modifier.size(36.dp), tint = Color.White) }
            }
        }
        AnimatedVisibility(visible = showControls && currentIndex > 0, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(currentIndex - 1) } }, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous", tint = Color.White) }
        }
        AnimatedVisibility(visible = showControls && currentIndex < effectiveItems.size - 1, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp)) {
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(currentIndex + 1) } }, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.ChevronRight, contentDescription = "Next", tint = Color.White) }
        }
    }
}

private fun deriveGalleryItemsFromAssets(context: android.content.Context): List<com.webtoapp.core.shell.GalleryShellItem> {
    return try {
        val assetEntries = context.assets.list("gallery")?.toList().orEmpty()
        if (assetEntries.isEmpty()) return emptyList()
        val entrySet = assetEntries.toSet()
        val normalized = assetEntries.map { name -> if (name.endsWith(".enc")) name.removeSuffix(".enc") else name }.toSet()
        val videoExts = setOf("mp4", "webm", "mkv", "avi", "mov", "3gp", "m4v"); val imageExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        normalized.filter { it.startsWith("item_") }.mapNotNull { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            val type = when { ext in videoExts -> "VIDEO"; ext in imageExts -> "IMAGE"; else -> null } ?: return@mapNotNull null
            val index = name.substringAfter("item_").substringBefore(".").toIntOrNull()
            val thumbName = index?.let { "thumb_$it.jpg" }
            val thumbExists = thumbName?.let { tn -> tn in normalized || "${tn}.enc" in entrySet } == true
            val displayName = index?.let { "Media ${it + 1}" } ?: name
            val sortKey = index ?: Int.MAX_VALUE
            com.webtoapp.core.shell.GalleryShellItem(id = name, assetPath = "gallery/$name", type = type, name = displayName, duration = 0, thumbnailPath = if (thumbExists) "gallery/$thumbName" else null) to sortKey
        }.sortedBy { it.second }.map { it.first }
    } catch (e: Exception) { emptyList() }
}

@Composable
fun ShellGalleryImageViewer(item: com.webtoapp.core.shell.GalleryShellItem, assetDecryptor: com.webtoapp.core.crypto.AssetDecryptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(item.assetPath) {
        isLoading = true
        try {
            val imageBytes = try { assetDecryptor.loadAsset(item.assetPath) } catch (e: Exception) { context.assets.open(item.assetPath).use { it.readBytes() } }
            bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {}
        isLoading = false
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isLoading) { CircularProgressIndicator(color = Color.White) } else { bitmap?.let { bmp -> Image(bitmap = bmp.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) } }
    }
}

@Composable
fun ShellGalleryVideoPlayer(item: com.webtoapp.core.shell.GalleryShellItem, assetDecryptor: com.webtoapp.core.crypto.AssetDecryptor, isCurrentPage: Boolean, isPlaying: Boolean, enableAudio: Boolean, showControls: Boolean, onPlayStateChange: (Boolean) -> Unit, onVideoEnded: () -> Unit, onToggleControls: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var surfaceHolder by remember { mutableStateOf<android.view.SurfaceHolder?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var tempVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    val isEncrypted = remember(item.assetPath) { assetDecryptor.isEncrypted(item.assetPath) }
    
    DisposableEffect(item.assetPath, isEncrypted) {
        val mediaPlayer = android.media.MediaPlayer()
        var assetFd: android.content.res.AssetFileDescriptor? = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    mediaPlayer.setOnPreparedListener { mp -> isPrepared = true; duration = mp.duration.toLong(); surfaceHolder?.let { mp.setDisplay(it) }; if (isPlaying) mp.start() }
                    mediaPlayer.setOnCompletionListener { onVideoEnded() }
                    mediaPlayer.setOnErrorListener { _, _, _ -> true }
                }
                if (!isEncrypted) {
                    try {
                        assetFd = context.assets.openFd(item.assetPath)
                        withContext(Dispatchers.Main) { mediaPlayer.setDataSource(assetFd!!.fileDescriptor, assetFd!!.startOffset, assetFd!!.length); mediaPlayer.prepareAsync() }
                        return@launch
                    } catch (e: Exception) {}
                    val ext = item.assetPath.substringAfterLast('.', "mp4")
                    val tempFile = java.io.File(context.cacheDir, "gallery_video_${System.currentTimeMillis()}.$ext")
                    context.assets.open(item.assetPath).use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    tempVideoFile = tempFile
                    withContext(Dispatchers.Main) { mediaPlayer.setDataSource(tempFile.absolutePath); mediaPlayer.prepareAsync() }
                } else {
                    val videoBytes = assetDecryptor.loadAsset(item.assetPath)
                    val ext = item.assetPath.substringAfterLast('.', "mp4")
                    val tempFile = java.io.File(context.cacheDir, "gallery_video_${System.currentTimeMillis()}.$ext")
                    tempFile.writeBytes(videoBytes)
                    tempVideoFile = tempFile
                    withContext(Dispatchers.Main) { mediaPlayer.setDataSource(tempFile.absolutePath); mediaPlayer.prepareAsync() }
                }
            } catch (e: Exception) {}
        }
        player = mediaPlayer
        onDispose { job.cancel(); try { assetFd?.close() } catch (e: Exception) {}; try { mediaPlayer.stop(); mediaPlayer.release() } catch (e: Exception) {}; player = null; isPrepared = false; tempVideoFile?.delete(); tempVideoFile = null }
    }
    
    LaunchedEffect(isPlaying, isPrepared, isCurrentPage) { player?.let { mp -> if (isPrepared) { if (isPlaying && isCurrentPage) { if (!mp.isPlaying) mp.start() } else { if (mp.isPlaying) mp.pause() } } } }
    LaunchedEffect(enableAudio, isPrepared) { player?.let { mp -> if (isPrepared) { mp.setVolume(if (enableAudio) 1f else 0f, if (enableAudio) 1f else 0f) } } }
    LaunchedEffect(isPlaying, isPrepared) { while (isPlaying && isPrepared) { player?.let { mp -> try { currentPosition = mp.currentPosition.toLong() } catch (e: Exception) {} }; kotlinx.coroutines.delay(100) } }
    
    Box(modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }, onDoubleTap = { offset -> val width = size.width; val seekAmount = 10000; player?.let { mp -> if (isPrepared) { val newPosition = if (offset.x < width / 2) { (mp.currentPosition - seekAmount).coerceAtLeast(0) } else { (mp.currentPosition + seekAmount).coerceAtMost(mp.duration) }; mp.seekTo(newPosition) } } }) }) {
        AndroidView(
            factory = { ctx ->
                android.view.SurfaceView(ctx).apply {
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) { surfaceHolder = holder; player?.setDisplay(holder) }
                        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { surfaceHolder = null }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.7f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = formatTimeMs(currentPosition), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        Slider(value = if (duration > 0) currentPosition.toFloat() / duration else 0f, onValueChange = { player?.seekTo((it * duration).toInt()); currentPosition = (it * duration).toLong() }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
                        Text(text = formatTimeMs(duration), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player?.let { mp -> if (isPrepared) mp.seekTo((mp.currentPosition - 10000).coerceAtLeast(0)) } }) { Icon(Icons.Default.Replay10, contentDescription = "Seek Back", tint = Color.White) }
                        IconButton(onClick = { onPlayStateChange(!isPlaying) }, modifier = Modifier.size(56.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", modifier = Modifier.size(36.dp), tint = Color.White) }
                        IconButton(onClick = { player?.let { mp -> if (isPrepared) mp.seekTo((mp.currentPosition + 10000).coerceAtMost(mp.duration)) } }) { Icon(Icons.Default.Forward10, contentDescription = "Seek Forward", tint = Color.White) }
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val seconds = (ms / 1000) % 60; val minutes = (ms / 1000 / 60) % 60; val hours = ms / 1000 / 60 / 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
}
