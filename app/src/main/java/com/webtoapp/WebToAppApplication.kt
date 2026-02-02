package com.webtoapp

import android.app.Application
import android.content.ComponentCallbacks2
import com.webtoapp.core.activation.ActivationManager
import com.webtoapp.core.adblock.AdBlocker
import com.webtoapp.core.announcement.AnnouncementManager
import com.webtoapp.core.logging.AppLogger
import com.webtoapp.core.shell.ShellModeManager
import com.webtoapp.data.database.AppDatabase
import com.webtoapp.data.repository.WebAppRepository
import com.webtoapp.data.repository.AppCategoryRepository

/**
 * Application class - Global dependency management
 */
class WebToAppApplication : Application() {

    // Lazy initialize database
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    // Repository
    val webAppRepository: WebAppRepository by lazy {
        WebAppRepository(database.webAppDao())
    }
    
    val appCategoryRepository: AppCategoryRepository by lazy {
        AppCategoryRepository(database.appCategoryDao())
    }

    // Core managers
    val activationManager: ActivationManager by lazy {
        ActivationManager(this)
    }

    val announcementManager: AnnouncementManager by lazy {
        AnnouncementManager(this)
    }

    val adBlocker: AdBlocker by lazy {
        AdBlocker()
    }

    // Shell mode manager (with exception protection)
    val shellModeManager: ShellModeManager by lazy {
        try {
            ShellModeManager(this)
        } catch (e: Exception) {
            android.util.Log.e("WebToAppApplication", "ShellModeManager initialization failed", e)
            // Return a new instance, let it retry on subsequent calls
            ShellModeManager(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 初始化运行日志系统（最先初始化，以便记录后续所有日志）
        try {
            AppLogger.init(this)
            AppLogger.system("Application", "onCreate started")
        } catch (e: Exception) {
            android.util.Log.e("WebToAppApplication", "AppLogger initialization failed", e)
        }
        
        // Preload Shell mode check (catch possible initialization errors)
        try {
            val isShell = shellModeManager.isShellMode()
            AppLogger.i("WebToAppApplication", "Shell mode pre-check: $isShell")
        } catch (e: Exception) {
            AppLogger.e("WebToAppApplication", "Shell mode pre-check failed", e)
        } catch (e: Error) {
            AppLogger.e("WebToAppApplication", "Shell mode pre-check critical error", Error(e))
        }
        
        AppLogger.system("Application", "onCreate completed")
    }
    
    override fun onTerminate() {
        AppLogger.system("Application", "onTerminate started")
        
        // Cleanup all singleton resources
        cleanupSingletons()
        
        // 关闭日志系统（最后关闭）
        AppLogger.shutdown()
        
        super.onTerminate()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        AppLogger.w("WebToAppApplication", "onLowMemory triggered")
        
        // Clear cache on low memory
        try {
            com.webtoapp.util.CacheManager.clearCookies()
            com.webtoapp.core.crypto.AesCryptoEngine.clearKeyCache()
            com.webtoapp.util.HtmlProjectProcessor.clearEncodingCache()
            adBlocker.clearPatternCache()
            AppLogger.i("WebToAppApplication", "Low memory, cache partially cleared")
        } catch (e: Exception) {
            AppLogger.e("WebToAppApplication", "Low memory cleanup failed", e)
        }
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN($level)"
        }
        AppLogger.d("WebToAppApplication", "onTrimMemory: $levelName")
    }
    
    /**
     * Cleanup all singleton resources
     */
    private fun cleanupSingletons() {
        try {
            AppLogger.d("WebToAppApplication", "Cleaning up singleton resources...")
            com.webtoapp.util.OfflineManager.release()
            com.webtoapp.core.extension.ExtensionManager.release()
            com.webtoapp.util.DownloadNotificationManager.release()
            com.webtoapp.core.crypto.AesCryptoEngine.clearKeyCache()
            AppDatabase.closeDatabase()
            AppLogger.i("WebToAppApplication", "Singleton resources cleaned up")
        } catch (e: Exception) {
            AppLogger.e("WebToAppApplication", "Failed to cleanup singleton resources", e)
        }
    }

    companion object {
        private lateinit var instance: WebToAppApplication

        fun getInstance(): WebToAppApplication = instance

        val repository: WebAppRepository
            get() = instance.webAppRepository
        
        val categoryRepository: AppCategoryRepository
            get() = instance.appCategoryRepository

        val activation: ActivationManager
            get() = instance.activationManager

        val announcement: AnnouncementManager
            get() = instance.announcementManager

        val adBlock: AdBlocker
            get() = instance.adBlocker

        val shellMode: ShellModeManager
            get() = instance.shellModeManager
    }
}
