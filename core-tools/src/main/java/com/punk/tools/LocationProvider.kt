package com.punk.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationProvider {
    private fun age(location: Location): Long = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(ctx: Context, maxAgeMs: Long = 30000, timeoutMs: Long = 15000): Map<String, Any> = withContext(Dispatchers.IO) {
        val fine = ToolRuntime.hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !ToolRuntime.hasPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return@withContext mapOf("ok" to false, "error" to "Conceda localização em Capacidades no PUNK.")
        try {
            val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOfNotNull(if(fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
                .filter { manager.isProviderEnabled(it) }
            if (providers.isEmpty()) return@withContext mapOf("ok" to false, "error" to "Localização desativada no aparelho.")
            val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.minByOrNull { age(it) }
            val fromCache = cached != null && age(cached) <= maxAgeMs
            if (!fromCache && !ToolRuntime.isUserVisible()) return@withContext mapOf("ok" to false, "error" to "Localização em cache ausente ou antiga. Abra o PUNK para obter uma posição atual.")
            val location = if (fromCache) cached else withTimeoutOrNull(timeoutMs.coerceIn(1000, 30000)) {
                awaitLocation(manager, providers)
            }
            if (location == null) return@withContext mapOf("ok" to false, "error" to "Prazo esgotado sem localização atual; nenhum dado antigo foi enviado.")
            if (!fromCache && age(location) > 30000) return@withContext mapOf("ok" to false, "error" to "Provedor retornou uma posição antiga. Aguarde um sinal de localização atual.")
            val result = mutableMapOf<String, Any>("ok" to true, "latitude" to location.latitude, "longitude" to location.longitude,
                "accuracy" to location.accuracy.toDouble(), "altitude" to location.altitude, "speed" to location.speed.toDouble(),
                "provider" to (location.provider ?: "unknown"), "timestamp" to location.time, "ageMs" to age(location), "fresh" to !fromCache)
            try {
                @Suppress("DEPRECATION")
                val address = Geocoder(ctx, Locale("pt", "BR")).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                if (address != null) {
                    result["address"] = address.getAddressLine(0).orEmpty()
                    result["city"] = address.subAdminArea ?: address.locality.orEmpty()
                    result["state"] = address.adminArea.orEmpty()
                    result["country"] = address.countryName.orEmpty()
                }
            } catch (_: Exception) { /* Coordinates remain valid if address lookup fails. */ }
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) { mapOf("ok" to false, "error" to (e.message ?: "Falha ao obter localização")) }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun awaitLocation(manager: LocationManager, providers: List<String>): Location = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        try {
            providers.forEach { manager.requestSingleUpdate(it, listener, Looper.getMainLooper()) }
        } catch (e: Exception) {
            manager.removeUpdates(listener)
            if (continuation.isActive) continuation.cancel(e)
        }
    }
}
