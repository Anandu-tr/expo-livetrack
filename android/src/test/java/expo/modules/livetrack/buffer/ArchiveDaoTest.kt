package expo.modules.livetrack.buffer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** In-memory Room test for [ArchiveDao] and [BufferDb.archiveAndDelete]. */
@RunWith(RobolectricTestRunner::class)
class ArchiveDaoTest {
  private lateinit var db: BufferDb
  private lateinit var archive: ArchiveDao
  private lateinit var points: PointDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      BufferDb::class.java,
    ).allowMainThreadQueries().build()
    archive = db.archiveDao()
    points = db.pointDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun archived(id: Long, t: Long, user: String = "u1") =
    ArchivedPointEntity(id, user, t, 9.0, 76.0, 1.0, 8.0, 50, false, "WALKING", false, t + 1)

  @Test
  fun inRange_filtersByUserAndHalfOpenBounds_orderedByT() {
    archive.insertAll(
      listOf(archived(3, 30), archived(1, 10), archived(2, 20), archived(4, 40), archived(9, 25, "u2")),
    )
    val rows = archive.inRange("u1", 10, 40)
    assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
    assertEquals(listOf(10L, 20L, 30L), rows.map { it.t })
  }

  @Test
  fun duplicateIdIsIgnored() {
    archive.insertAll(listOf(archived(1, 10)))
    archive.insertAll(listOf(archived(1, 99)))
    val rows = archive.inRange("u1", 0, 1000)
    assertEquals(1, rows.size)
    assertEquals(10L, rows[0].t)
  }

  @Test
  fun prune_dropsOnlyRowsOlderThanCutoff() {
    archive.insertAll(listOf(archived(1, 10), archived(2, 20), archived(3, 30)))
    assertEquals(1, archive.prune(20))
    assertEquals(listOf(2L, 3L), archive.inRange("u1", 0, 1000).map { it.id })
  }

  @Test
  fun trimToCap_dropsOldestFirst() {
    archive.insertAll((1L..10L).map { archived(it, it * 10) })
    assertEquals(4, archive.trimToCap(6))
    assertEquals((5L..10L).toList(), archive.inRange("u1", 0, 1000).map { it.id })
    assertEquals(0, archive.trimToCap(6))
  }

  @Test
  fun countOldestClear() {
    assertEquals(0, archive.countAll())
    assertNull(archive.oldestT())
    archive.insertAll(listOf(archived(1, 50), archived(2, 20)))
    assertEquals(2, archive.countAll())
    assertEquals(20L, archive.oldestT())
    archive.clear()
    assertEquals(0, archive.countAll())
  }

  @Test
  fun archiveAndDelete_movesLocationRowsAndDropsEvents() {
    val loc1 = points.insert(PointEntity(userId = "u1", t = 1L, lat = 1.0, lng = 1.0))
    val loc2 = points.insert(PointEntity(userId = "u1", t = 2L, lat = 2.0, lng = 2.0))
    val ev = points.insert(PointEntity(userId = "u1", t = 3L, eventType = "HEARTBEAT"))
    val rows = points.unsynced(50)
    assertEquals(3, rows.size)

    db.archiveAndDelete(rows, uploadedAt = 1000L, archiveOn = true)

    assertEquals(0, points.count())
    val kept = archive.inRange("u1", 0, 1000)
    assertEquals(listOf(loc1, loc2), kept.map { it.id })
    assertTrue(kept.none { it.id == ev })
    assertEquals(1000L, kept[0].uploadedAt)
  }

  @Test
  fun archiveAndDelete_withArchiveOff_onlyDeletes() {
    points.insert(PointEntity(userId = "u1", t = 1L, lat = 1.0, lng = 1.0))
    db.archiveAndDelete(points.unsynced(50), uploadedAt = 1000L, archiveOn = false)
    assertEquals(0, points.count())
    assertEquals(0, archive.countAll())
  }

  @Test
  fun locationsInRange_excludesEventsAndOtherUsers() {
    points.insert(PointEntity(userId = "u1", t = 5L, lat = 1.0, lng = 1.0))
    points.insert(PointEntity(userId = "u1", t = 6L, eventType = "HEARTBEAT"))
    points.insert(PointEntity(userId = "u2", t = 7L, lat = 1.0, lng = 1.0))
    assertEquals(listOf(5L), points.locationsInRange("u1", 0, 100).map { it.t })
  }
}
