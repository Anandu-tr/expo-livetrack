package expo.modules.livetrack.buffer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-memory Room test for [PointDao].
 *
 * REQUIRES A GRADLE HOST TO RUN: needs Robolectric (`org.robolectric:robolectric`)
 * + `androidx.test:core` + `androidx.room:room-testing` on the test classpath and
 * a configured Android `unitTests` block. It CANNOT be executed in this
 * environment (no gradle host / SDK). // VERIFY: run via `./gradlew :test` on a host app.
 */
@RunWith(RobolectricTestRunner::class)
class PointDaoTest {
  private lateinit var db: BufferDb
  private lateinit var dao: PointDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      BufferDb::class.java,
    )
      .allowMainThreadQueries() // test-only: lets us call the synchronous DAO inline.
      .build()
    dao = db.pointDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun unsynced_returnsRowsAscendingByT_andDeleteByIdsClearsThem() {
    // Insert out of time order: t=2 first, then t=1.
    val id2 = dao.insert(PointEntity(userId = "u1", t = 2L, lat = 10.0, lng = 20.0))
    val id1 = dao.insert(PointEntity(userId = "u1", t = 1L, lat = 11.0, lng = 21.0))

    assertEquals(2, dao.count())

    // unsynced(50) must return them t-ascending (t=1 row before t=2 row).
    val rows = dao.unsynced(50)
    assertEquals(2, rows.size)
    assertEquals(1L, rows[0].t)
    assertEquals(2L, rows[1].t)
    assertEquals(id1, rows[0].id)
    assertEquals(id2, rows[1].id)

    // deleteByIds clears the buffer.
    dao.deleteByIds(listOf(id1, id2))
    assertEquals(0, dao.count())
    assertTrue(dao.unsynced(50).isEmpty())
  }

  @Test
  fun unsynced_tiebreaksOnIdSoBatchesAreStableAcrossRuns() {
    // Same millisecond: without the `id ASC` tiebreak the order is unspecified, and
    // the attempts counter would smear across rows instead of converging.
    val a = dao.insert(PointEntity(userId = "u1", t = 7L, lat = 1.0, lng = 1.0))
    val b = dao.insert(PointEntity(userId = "u1", t = 7L, lat = 2.0, lng = 2.0))
    val c = dao.insert(PointEntity(userId = "u1", t = 7L, lat = 3.0, lng = 3.0))

    repeat(3) {
      assertEquals(listOf(a, b), dao.unsynced(2).map { row -> row.id })
    }
    assertEquals(listOf(a, b, c), dao.unsynced(50).map { row -> row.id })
  }

  @Test
  fun newRowsStartAtZeroAttempts_andIncrementAttemptsBumpsOnlyNamedIds() {
    val id1 = dao.insert(PointEntity(userId = "u1", t = 1L, lat = 1.0, lng = 1.0))
    val id2 = dao.insert(PointEntity(userId = "u1", t = 2L, eventType = "HEARTBEAT"))

    assertTrue(dao.unsynced(50).all { it.attempts == 0 })

    assertEquals(1, dao.incrementAttempts(listOf(id1)))
    assertEquals(1, dao.incrementAttempts(listOf(id1)))

    val byId = dao.unsynced(50).associateBy { it.id }
    assertEquals(2, byId.getValue(id1).attempts)
    assertEquals(0, byId.getValue(id2).attempts)
  }

  @Test
  fun evictionQueries_onlyTouchRowsAtOrOverTheCap() {
    val stuck = dao.insert(PointEntity(userId = "u1", t = 1L, lat = 1.0, lng = 1.0))
    val fresh = dao.insert(PointEntity(userId = "u1", t = 2L, lat = 2.0, lng = 2.0))

    repeat(3) { dao.incrementAttempts(listOf(stuck)) }
    dao.incrementAttempts(listOf(fresh))

    // Cap of 3: only `stuck` qualifies.
    assertEquals(listOf(stuck), dao.idsExceedingAttempts(3, 20))
    assertEquals(1L, dao.oldestExceedingAttempts(3))

    assertEquals(1, dao.deleteExceedingAttempts(3))
    assertEquals(1, dao.count())
    assertEquals(listOf(fresh), dao.unsynced(50).map { it.id })

    // Nothing left at/over the cap — the eviction path must now be a no-op.
    assertTrue(dao.idsExceedingAttempts(3, 20).isEmpty())
    assertEquals(null, dao.oldestExceedingAttempts(3))
    assertEquals(0, dao.deleteExceedingAttempts(3))
  }
}
