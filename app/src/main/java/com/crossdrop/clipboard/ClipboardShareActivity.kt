package com.crossdrop.clipboard

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.crossdrop.CrossDropApp

/**
 * Invisible: opened by the Quick Settings tile only so that CrossDrop has window focus for a moment, which Android
 * requires before an app may read the clipboard. Reads it, hands it over and closes.
 */
class ClipboardShareActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))      // a window with a view, so it really takes focus
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        (application as CrossDropApp).container.phoneClipboard.shareNow()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
