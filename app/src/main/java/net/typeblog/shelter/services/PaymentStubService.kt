package net.typeblog.shelter.services

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * A fake NFC payment service for the main profile.
 * Enabled only through settings (component is disabled by default in the
 * manifest). It never handles any APDU; it exists so that HCE based payment
 * apps can be tricked into thinking the device has a "payment default"
 * capability, mirroring the original behavior.
 */
class PaymentStubService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle): ByteArray? {
        // We do not handle anything
        notifyUnhandled()
        return null
    }

    override fun onDeactivated(reason: Int) {
        // Nothing to do
    }
}