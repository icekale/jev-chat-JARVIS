package com.jev.probe

import android.app.Application
import com.jev.probe.core.A11yComponents
import com.jev.probe.jev.JevQuestions

class JevApp : Application() {
    override fun onCreate() {
        super.onCreate()
        JevQuestions.init(this)
        A11yComponents.apply(this)
    }
}
