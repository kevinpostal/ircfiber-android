package com.kevinpostal.fiberirc.data.voice

/** One service lifetime. A legitimate microphone type is sticky until the service ends. */
internal class VoiceForegroundQualification {
    var microphone: Boolean = false
        private set
    fun promote(requestMicrophone: Boolean, visible: Boolean, permissionGranted: Boolean) {
        if (requestMicrophone && !microphone) {
            check(visible && permissionGranted) { "Open the app and allow microphone access before speaking" }
            microphone = true
        }
    }
}
