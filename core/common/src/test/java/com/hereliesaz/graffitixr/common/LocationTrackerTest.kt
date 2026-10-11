package com.hereliesaz.graffitixr.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Location is requested when a capture needs it, which is after the AR session started its
 * tracker. A start made without permission must therefore take effect once permission arrives.
 */
class LocationTrackerTest {

    private val context: Context = mockk(relaxed = true)
    private val client: FusedLocationProviderClient = mockk(relaxed = true)
    private var granted = false

    @Before
    fun setup() {
        mockkStatic(LocationServices::class, ContextCompat::class, Log::class, Looper::class)
        every { LocationServices.getFusedLocationProviderClient(any<Context>()) } returns client
        // LocationTracker calls ActivityCompat.checkSelfPermission, which is ContextCompat's static
        // inherited by name: the bytecode targets ContextCompat, so that is the class to mock.
        every { ContextCompat.checkSelfPermission(any(), any()) } answers {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        every { Log.i(any(), any()) } returns 0
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        mockLocationRequestBuilder()
    }

    private fun mockLocationRequestBuilder() {
        io.mockk.mockkConstructor(LocationRequest.Builder::class)
        every { anyConstructed<LocationRequest.Builder>().setWaitForAccurateLocation(any()) } answers { self as LocationRequest.Builder }
        every { anyConstructed<LocationRequest.Builder>().setMinUpdateIntervalMillis(any()) } answers { self as LocationRequest.Builder }
        every { anyConstructed<LocationRequest.Builder>().setMaxUpdateDelayMillis(any()) } answers { self as LocationRequest.Builder }
        every { anyConstructed<LocationRequest.Builder>().build() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a start without permission begins updates once permission is granted`() {
        val tracker = LocationTracker(context)
        tracker.startLocationUpdates { }
        verify(exactly = 0) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }

        granted = true
        LocationTracker.onPermissionsChanged()

        verify(exactly = 1) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
    }

    @Test
    fun `a stopped tracker does not start when permission arrives later`() {
        val tracker = LocationTracker(context)
        tracker.startLocationUpdates { }
        tracker.stopLocationUpdates()

        granted = true
        LocationTracker.onPermissionsChanged()

        verify(exactly = 0) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
    }

    @Test
    fun `a denial leaves the start pending without requesting updates`() {
        val tracker = LocationTracker(context)
        tracker.startLocationUpdates { }
        LocationTracker.onPermissionsChanged()
        verify(exactly = 0) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
        tracker.stopLocationUpdates()
    }
}
