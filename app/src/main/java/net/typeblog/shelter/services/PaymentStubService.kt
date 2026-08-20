package net.typeblog.shelter.services

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class PaymentStubService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle): ByteArray? {
        // We do not handle anything.
        notifyUnhandled()
        return null
    }

    override fun onDeactivated(reason: Int) {
        // Nothing to do.
    }
}
