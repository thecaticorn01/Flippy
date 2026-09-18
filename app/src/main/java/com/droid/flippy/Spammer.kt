package com.droid.flippy

interface Spammer {
    fun isSpamming(): Boolean
    fun start()
    fun stop()
    fun setBlinkRunnable(runnable: Runnable?)
    fun getBlinkRunnable(): Runnable?
}
