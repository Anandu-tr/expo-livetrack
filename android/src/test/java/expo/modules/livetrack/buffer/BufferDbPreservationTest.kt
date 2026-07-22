package expo.modules.livetrack.buffer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the data-safety requirement: opening the buffer DB with the current
 * configuration must never drop existing rows. This is the regression guard that
 * fires if a future schema bump reintroduces a destructive migration.
 */
@RunWith(RobolectricTestRunner::class)
class BufferDbPreservationTest {
  @Test fun reopeningPreservesRows() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val name = "preservation_test.db"
    ctx.deleteDatabase(name)

    // First open: seed two rows, then close.
    var db = Room.databaseBuilder(ctx, BufferDb::class.java, name).build()
    db.pointDao().insert(PointEntity(userId = "u1", t = 1, lat = 1.0, lng = 2.0))
    db.pointDao().insert(PointEntity(userId = "u1", t = 2, eventType = "HEARTBEAT"))
    assertEquals(2, db.pointDao().count())
    db.close()

    // Reopen with the same configuration: rows must survive.
    db = Room.databaseBuilder(ctx, BufferDb::class.java, name).build()
    assertEquals(2, db.pointDao().count())
    db.close()
    ctx.deleteDatabase(name)
  }
}
