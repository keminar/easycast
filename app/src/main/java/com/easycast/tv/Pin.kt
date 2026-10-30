package com.easycast.tv

/** Rules for the web PIN, kept free of Android classes so they can be unit-tested. */
object PinPolicy {
    /**
     * The PIN is replaced once per calendar day. [savedDay] is the day it was created (null if unknown).
     * Never while something plays: that would lock the web page out in the middle of a video.
     */
    fun rotationDue(savedDay: String?, today: String, playing: Boolean): Boolean =
        savedDay != today && !playing

    /** A random 4-digit PIN that differs from [old]. [nextInt] returns a value in from..to (inclusive). */
    fun newPin(old: String, nextInt: (Int, Int) -> Int = { from, to -> (from..to).random() }): String {
        var pin: String
        do {
            pin = nextInt(1000, 9999).toString()
        } while (pin == old)
        return pin
    }
}
