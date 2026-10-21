package com.hereliesaz.graffitixr.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * A utility class for tracking device location using the Fused Location Provider.
 *
 * @property context The application context.
 */
class LocationTracker(private val context: Context) {

    private var fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null

    /**
     * A start requested while location permission was missing. Location is asked for at the moment
     * a capture needs it (MainActivity, when target capture begins) — usually AFTER the AR session,
     * and with it this tracker, has already started. Remembering the request lets
     * [onPermissionsChanged] begin updates the moment the artist grants it, instead of the session
     * recording no location until AR is restarted.
     */
    @Volatile private var pendingUpdate: ((Location) -> Unit)? = null

    /**
     * Starts receiving location updates.
     *
     * @param onLocationUpdate A callback function that is invoked with the new [Location].
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(onLocationUpdate: (Location) -> Unit) {
        if (!hasPermissions()) {
            Log.i("LocationTracker", "Location permission not granted yet; updates start when it is")
            pendingUpdate = onLocationUpdate
            synchronized(awaitingPermission) { awaitingPermission.add(this) }
            return
        }
        pendingUpdate = null
        synchronized(awaitingPermission) { awaitingPermission.remove(this) }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5000)
            .setMaxUpdateDelayMillis(10000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    onLocationUpdate(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LocationTracker", "SecurityException requesting location updates", e)
        }
    }

    /** A [requestSingleFix] that waits for permission (see [pendingUpdate]). */
    @Volatile private var pendingSingle: ((Location) -> Unit)? = null
    private var singleFixToken: com.google.android.gms.tasks.CancellationTokenSource? = null

    /**
     * One location fix at balanced-power accuracy (cell and Wi-Fi first, GPS only if it must), for
     * the capture about to be geotagged: no subscription stays open. Without permission it waits,
     * like [startLocationUpdates], and runs the moment permission is granted.
     */
    @SuppressLint("MissingPermission")
    fun requestSingleFix(onFix: (Location) -> Unit) {
        if (!hasPermissions()) {
            pendingSingle = onFix
            synchronized(awaitingPermission) { awaitingPermission.add(this) }
            return
        }
        pendingSingle = null
        singleFixToken?.cancel()
        val token = com.google.android.gms.tasks.CancellationTokenSource().also { singleFixToken = it }
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
                .addOnSuccessListener { location -> if (location != null) onFix(location) }
        } catch (e: SecurityException) {
            Log.e("LocationTracker", "SecurityException requesting a location fix", e)
        }
    }

    /**
     * Stops receiving location updates.
     */
    fun stopLocationUpdates() {
        pendingSingle = null
        singleFixToken?.cancel()
        singleFixToken = null
        pendingUpdate = null
        synchronized(awaitingPermission) { awaitingPermission.remove(this) }
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    /**
     * Checks if the required location permissions are granted.
     *
     * @return True if ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION are granted, false otherwise.
     */
    /** Starts the updates a permission-less [startLocationUpdates] deferred, if permission is now held. */
    private fun retryPendingStart() {
        if (!hasPermissions()) return
        pendingSingle?.let { requestSingleFix(it) }
        pendingUpdate?.let { startLocationUpdates(it) }
    }

    fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** Trackers holding a deferred start. Weak: a tracker dropped without stop must not leak. */
        private val awaitingPermission: MutableSet<LocationTracker> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /**
         * Call after a location-permission request returns. Every tracker whose start was deferred
         * for lack of permission begins its updates now if the grant came through.
         */
        @JvmStatic
        fun onPermissionsChanged() {
            val waiting = synchronized(awaitingPermission) { awaitingPermission.toList() }
            waiting.forEach { it.retryPendingStart() }
        }
    }
}
