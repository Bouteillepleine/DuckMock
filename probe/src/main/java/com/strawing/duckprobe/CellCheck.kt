package com.strawing.duckprobe

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.Log

class CellCheck : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        fun say(line: String) = Log.i("DuckProbe", line)
        say("CELL — what this app can read about the network around it")

        val tm = context.getSystemService(TelephonyManager::class.java)
        if (tm == null) {
            say("  no TelephonyManager")
            return
        }

        say("  network operator   = ${runCatching { tm.networkOperator }.getOrNull()}")
        say("  operator name      = ${runCatching { tm.networkOperatorName }.getOrNull()}")
        say("  network country    = ${runCatching { tm.networkCountryIso }.getOrNull()}")
        say("  sim country        = ${runCatching { tm.simCountryIso }.getOrNull()}")
        say("  sim operator       = ${runCatching { tm.simOperator }.getOrNull()}")

        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            say("  (no location permission, cell list not attempted)")
            return
        }

        val cells = runCatching { tm.allCellInfo }.getOrNull()
        say("  cells visible      = ${cells?.size ?: "refused"}")
        cells?.take(4)?.forEach { say("    ${describe(it)}") }

        val country = runCatching { tm.networkCountryIso }.getOrNull()
        if (!country.isNullOrBlank() || (cells?.size ?: 0) > 0) {
            say("  VERDICT: the network still says where this device really is")
        } else {
            say("  VERDICT: nothing here reveals the country")
        }
    }

    private fun describe(info: CellInfo): String = when (val id = info.cellIdentity) {
        is CellIdentityLte -> "LTE mcc=${id.mccString} mnc=${id.mncString} tac=${id.tac} ci=${id.ci}"
        is CellIdentityNr -> "NR  mcc=${id.mccString} mnc=${id.mncString} tac=${id.tac} nci=${id.nci}"
        is CellIdentityGsm -> "GSM mcc=${id.mccString} mnc=${id.mncString} lac=${id.lac} cid=${id.cid}"
        is CellIdentityWcdma -> "WCDMA mcc=${id.mccString} mnc=${id.mncString} lac=${id.lac} cid=${id.cid}"
        else -> id?.javaClass?.simpleName ?: "unknown"
    }
}
