package expo.modules.livetrack.buffer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Synchronous DAO over `archived_points`. Blocking like [PointDao]: call off the
 * main thread. Abstract class (not interface) so [trimToCap] can be a real
 * `@Transaction` method.
 */
@Dao
abstract class ArchiveDao {

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract fun insertAll(rows: List<ArchivedPointEntity>)

  /** Rows for one user in `[from, to)`, oldest first (`id` tiebreak like the buffer). */
  @Query(
    "SELECT * FROM archived_points WHERE userId = :userId AND t >= :from AND t < :to " +
      "ORDER BY t ASC, id ASC",
  )
  abstract fun inRange(userId: String, from: Long, to: Long): List<ArchivedPointEntity>

  /**
   * Rows for one user with id > sinceId, oldest first, capped at [limit] — export
   * cursor for the TEMPORARY Supabase diagnostics upload (trackingLog.ts). `id` is
   * the original points.id, monotonically increasing, so this never re-returns a
   * row already exported without re-querying from an older cursor.
   */
  @Query(
    "SELECT * FROM archived_points WHERE userId = :userId AND id > :sinceId " +
      "ORDER BY id ASC LIMIT :limit",
  )
  abstract fun rowsAfterId(userId: String, sinceId: Long, limit: Int): List<ArchivedPointEntity>

  /** Drop rows older than the retention cutoff. Returns rows deleted. */
  @Query("DELETE FROM archived_points WHERE t < :cutoff")
  abstract fun prune(cutoff: Long): Int

  @Query("SELECT COUNT(*) FROM archived_points")
  abstract fun countAll(): Int

  @Query("SELECT MIN(t) FROM archived_points")
  abstract fun oldestT(): Long?

  @Query("DELETE FROM archived_points")
  abstract fun clear()

  @Query(
    "DELETE FROM archived_points WHERE id IN " +
      "(SELECT id FROM archived_points ORDER BY t ASC, id ASC LIMIT :n)",
  )
  protected abstract fun deleteOldest(n: Int): Int

  /** Drop the oldest rows until at most [cap] remain. Returns rows dropped. */
  @Transaction
  open fun trimToCap(cap: Int): Int {
    val over = countAll() - cap
    return if (over > 0) deleteOldest(over) else 0
  }
}
