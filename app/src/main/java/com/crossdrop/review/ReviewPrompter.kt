package com.crossdrop.review

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.crossdrop.core.review.ReviewPolicy
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Play Store rating, rarely and at a good moment (see [ReviewPolicy]): after a run of transfers
 * that worked, when the person closes the "done" screen. Play's own in-app review sheet does the asking, so
 * nothing about the person or the app's use is sent from here; Play may also decide to show nothing (its
 * own quota), and on a phone without Play it quietly does nothing.
 */
class ReviewPrompter(context: Context) {
    private val prefs = context.getSharedPreferences("review", Context.MODE_PRIVATE)

    private val policy = ReviewPolicy(object : ReviewPolicy.Store {
        override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
        override fun putLong(key: String, value: Long) = prefs.edit { putLong(key, value) }
    })

    /** A transfer ended well ([success]) or badly; cancels and declines aren't reported. */
    fun onTransfer(success: Boolean) = policy.onTransfer(success)

    /** Call when the person has just finished with a successful transfer (closed its screen). */
    fun maybeAsk(activity: Activity) {
        if (activity.isFinishing || !policy.shouldAsk()) return
        // Whatever Play decides, this counts as the ask: no retrying sooner because it stayed silent.
        policy.asked()
        // Let the "done" screen finish closing first, so the sheet doesn't land on a moving screen.
        activity.window?.decorView?.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            runCatching {
                val manager = ReviewManagerFactory.create(activity)
                manager.requestReviewFlow().addOnCompleteListener { task ->
                    if (task.isSuccessful && !activity.isFinishing) runCatching { manager.launchReviewFlow(activity, task.result) }
                }
            }
        }, 700)
    }
}
