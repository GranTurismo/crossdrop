package com.crossdrop

import android.app.Application
import com.crossdrop.di.AppContainer

class CrossDropApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
