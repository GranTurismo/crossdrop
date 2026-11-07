package com.crossdrop.platform

/** Minimal stand-in for android.util.Log so shared code keeps its call shape. */
object Log {
    @Volatile var verbose = System.getProperty("crossdrop.debug") != null

    fun i(tag: String, msg: String) { if (verbose) System.err.println("I/$tag: $msg") }
    fun w(tag: String, msg: String, t: Throwable? = null) {
        System.err.println("W/$tag: $msg" + (t?.let { " — ${it.message}" } ?: ""))
        if (verbose) t?.printStackTrace()
    }
}
