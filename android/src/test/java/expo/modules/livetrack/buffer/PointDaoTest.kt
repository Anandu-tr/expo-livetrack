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
}
