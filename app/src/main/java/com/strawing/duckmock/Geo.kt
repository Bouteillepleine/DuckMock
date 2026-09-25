package com.strawing.duckmock

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale

object Geo {

    fun available(): Boolean = runCatching { Geocoder.isPresent() }.getOrDefault(false)

    fun label(address: Address): String {
        address.getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = listOfNotNull(
            address.featureName,
            address.thoroughfare,
            address.locality,
            address.countryName,
        ).distinct()
        return parts.joinToString(", ")
            .ifBlank { "%.5f, %.5f".format(Locale.ROOT, address.latitude, address.longitude) }
    }

    fun search(context: Context, query: String, onResult: (List<Address>, String?) -> Unit) {
        if (query.isBlank()) {
            onResult(emptyList(), "Type an address first.")
            return
        }
        if (!available()) {
            onResult(emptyList(), "This device has no geocoder.")
            return
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) =
                        onResult(addresses, if (addresses.isEmpty()) "Nothing found." else null)

                    override fun onError(message: String?) =
                        onResult(emptyList(), message ?: "The geocoder failed.")
                })
            }.onFailure { onResult(emptyList(), it.message ?: "The geocoder failed.") }
            return
        }
        Thread {
            @Suppress("DEPRECATION")
            val result = runCatching { geocoder.getFromLocationName(query, 5) }
            val addresses = result.getOrNull().orEmpty()
            onResult(
                addresses,
                when {
                    result.isFailure -> result.exceptionOrNull()?.message ?: "The geocoder failed."
                    addresses.isEmpty() -> "Nothing found."
                    else -> null
                },
            )
        }.apply { isDaemon = true }.start()
    }

    fun describe(context: Context, lat: Double, lon: Double, onResult: (String?) -> Unit) {
        if (!available()) {
            onResult(null)
            return
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) =
                        onResult(addresses.firstOrNull()?.let { label(it) })

                    override fun onError(message: String?) = onResult(null)
                })
            }.onFailure { onResult(null) }
            return
        }
        Thread {
            @Suppress("DEPRECATION")
            val addresses = runCatching { geocoder.getFromLocation(lat, lon, 1) }.getOrNull()
            onResult(addresses?.firstOrNull()?.let { label(it) })
        }.apply { isDaemon = true }.start()
    }
}
