package expo.modules.livetrack.buffer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Synchronous DAO over the `points` buffer.
 *
 * All methods are blocking (non-suspend): callers MUST invoke them off the main
 * thread (Room throws on main-thread queries unless `allowMainThreadQueries()`
 * is set, which we only do in tests). The service/module wrap these in
 * `Dispatchers.IO`.
 */
@Dao
interface PointDao {
  /** Insert one row; returns the auto-generated row id. */
  @Insert
  fun insert(p: PointEntity): Long

  /** Oldest-first page of buffered rows for the uploader to flush. */
  @Query("SELECT * FROM points ORDER BY t ASC LIMIT :limit")
  fun unsynced(limit: Int): List<PointEntity>

  /** Delete rows by id (called after a server ACK). */
  @Query("DELETE FROM points WHERE id IN (:ids)")
  fun deleteByIds(ids: List<Long>)

  /** Total buffered rows (used by getState.bufferedCount). */
  @Query("SELECT COUNT(*) FROM points")
  fun count(): Int
}
