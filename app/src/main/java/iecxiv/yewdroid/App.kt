package iecxiv.yewdroid

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        DownloadHub.appContext = applicationContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        DownloadHub.restore()
        // "Clear the downloads list on close": when the main screen is closed for good (not recreated),
        // the finished downloads are removed from the list. If the process is killed instead, the list is
        // emptied the next time it is loaded (android_downloads.configure).
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity && activity.isFinishing && UiPrefs.clearDownloadsOnClose(this@App)) {
                    runCatching { Python.getInstance().getModule("android_downloads").callAttr("clear_finished") }
                }
            }

            // A track that finished loading while the app was in the background could not take audio focus
            // (Android refuses it); it starts now that the app is back in front.
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) TerminalHub.onAppForeground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
