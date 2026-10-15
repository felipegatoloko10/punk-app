package com.punk.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object LocationProvider {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(ctx: Context): Map<String, Any> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Any>()
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                result["ok"] = false
                result["error"] = "LocationManager indisponível"
                return@withContext result
            }

            val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            var bestLoc: Location? = null
            if (isGpsEnabled) {
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (loc != null) bestLoc = loc
            }

            if (isNetEnabled) {
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    if (bestLoc == null || loc.time > bestLoc.time) {
                        bestLoc = loc
                    }
                }
            }

            if (bestLoc != null) {
                result["ok"] = true
                result["latitude"] = bestLoc.latitude
                result["longitude"] = bestLoc.longitude
                result["accuracy"] = bestLoc.accuracy.toDouble()
                result["altitude"] = bestLoc.altitude
                result["speed"] = bestLoc.speed.toDouble()
                result["provider"] = bestLoc.provider ?: "unknown"
                result["timestamp"] = bestLoc.time

                try {
                    val geocoder = Geocoder(ctx, Locale("pt", "BR"))
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(bestLoc.latitude, bestLoc.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        result["address"] = addr.getAddressLine(0) ?: ""
                        result["city"] = addr.subAdminArea ?: addr.locality ?: ""
                        result["state"] = addr.adminArea ?: ""
                        result["country"] = addr.countryName ?: ""
                    }
                } catch (e: Exception) {
                    result["geocoder_error"] = e.message ?: "Falha ao resolver endereço"
                }
            } else {
                result["ok"] = false
                result["error"] = "Nenhuma coordenada conhecida recente. Ative o GPS/Localização no aparelho."
            }
        } catch (e: Exception) {
            result["ok"] = false
            result["error"] = e.message ?: "Erro ao obter localização"
        }
        result
    }
}
