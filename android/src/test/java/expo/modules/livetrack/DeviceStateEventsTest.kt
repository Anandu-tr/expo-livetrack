package expo.modules.livetrack

import android.content.Context
import android.location.LocationManager
import android.os.PowerManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Production (POCO/MIUI, Oct 2026): location was switched on while the killed
 * service had no receiver, so no LOCATION_ON was ever recorded; permission and
 * battery-optimisation changes were only noticed when the app was opened.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceStateEventsTest {
  private lateinit var context: Context
  private lateinit var db: BufferDb
  private lateinit var dao: PointDao

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, BufferDb::class.java)
      .allowMainThreadQueries() // test-only: lets us call the synchronous DAO inline.
      .build()
    dao = db.pointDao()
  }

  @After
  fun tearDown() {
    db.close()
    TrackedPosition.latLng = null
  }

  private val prefs get() = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

  private fun gps(enabled: Boolean) {
    val lm = shadowOf(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
    lm.setLocationEnabled(enabled)
    lm.setProviderEnabled(LocationManager.GPS_PROVIDER, enabled)
  }

  private fun events() = dao.unsynced(100).filter { it.eventType != null }

  @Test
  fun locationSwitchedOnWhileNothingWasListeningIsRecordedByReconcile() {
    prefs.edit().putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, false).commit()
    gps(true)
    DeviceStateEvents.reconcile(context, dao)
    val e = events().single()
    assertEquals("LOCATION_ON", e.eventType)
    assertEquals(DeviceStateEvents.REASON_RECONCILE, e.act)
    assertTrue(prefs.getBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, false))
  }

  @Test
  fun reconcileRecordsAChangeOnlyOnce() {
    prefs.edit().putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, false).commit()
    gps(true)
    DeviceStateEvents.reconcile(context, dao)
    DeviceStateEvents.reconcile(context, dao)
    assertEquals(1, events().size)
  }

  @Test
  fun unchangedStateRecordsNothing() {
    prefs.edit().putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, true).commit()
    gps(true)
    // Permission and battery state are seen for the first time: a baseline, not an event.
    DeviceStateEvents.reconcile(context, dao)
    assertEquals(0, events().size)
  }

  @Test
  fun liveReceiverChangeCarriesNoReason() {
    prefs.edit().putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, true).commit()
    gps(false)
    assertTrue(DeviceStateEvents.checkLocation(context, dao, reason = null))
    val e = events().single()
    assertEquals("LOCATION_OFF", e.eventType)
    assertNull(e.act)
  }

  @Test
  fun locationOffCarriesTheTrackedPosition() {
    // Production (Oct 2026): every LOCATION_OFF/ON row came without a position,
    // because the system's last-known location is null while the provider is off.
    prefs.edit().putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, true).commit()
    gps(false)
    TrackedPosition.latLng = 9.9601 to 76.2891
    DeviceStateEvents.checkLocation(context, dao, reason = null)
    val e = events().single()
    assertEquals(9.9601 to 76.2891, e.lat to e.lng)
  }

  @Test
  fun permissionLostWhileTheAppWasClosedIsRecordedByReconcile() {
    prefs.edit()
      .putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, true)
      .putBoolean(Prefs.KEY_LAST_PERMISSION_GRANTED, true)
      .commit()
    gps(true)
    // Robolectric grants no runtime permissions by default: location access is gone.
    DeviceStateEvents.reconcile(context, dao)
    val e = events().single()
    assertEquals("PERMISSION_REVOKED", e.eventType)
    assertEquals(DeviceStateEvents.REASON_RECONCILE, e.act)
  }

  @Test
  fun batteryOptimisationReEnabledIsRecordedByReconcile() {
    prefs.edit()
      .putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, true)
      .putBoolean(Prefs.KEY_LAST_BATTERY_OPT_IGNORED, true)
      .commit()
    gps(true)
    shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .setIgnoringBatteryOptimizations(context.packageName, false)
    DeviceStateEvents.reconcile(context, dao)
    assertEquals(listOf("BATTERY_OPT_ON"), events().map { it.eventType })
  }
}
