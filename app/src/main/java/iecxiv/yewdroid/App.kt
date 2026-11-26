package iecxiv.yewdroid

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadHub.appContext = applicationContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        DownloadHub.restore()
    }
}
