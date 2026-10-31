package com.easycast.tv

/** Rules for the web PIN, kept free of Android classes so they can be unit-tested. */
object PinPolicy {
    /**
     * A random 4-digit PIN that differs from [old]. The PIN is only ever generated when the user
     * asks for it (the "更换口令" button); it never rotates on its own, so a saved PIN stays valid
     * until it is changed by hand. [nextInt] returns a value in from..to (inclusive).
     */
    fun newPin(old: String, nextInt: (Int, Int) -> Int = { from, to -> (from..to).random() }): String {
        var pin: String
        do {
            pin = nextInt(1000, 9999).toString()
        } while (pin == old)
        return pin
    }
}
