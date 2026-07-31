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

  /**
   * Oldest-first page of buffered rows for the uploader to flush.
   *
   * `id ASC` is a deterministic tiebreak, not decoration: rows captured within the
   * same millisecond must land in the SAME batch on every run, otherwise the
   * `attempts` counter smears across rows instead of converging on the stuck ones.
   */
  @Query("SELECT * FROM points ORDER BY t ASC, id ASC LIMIT :limit")
  fun unsynced(limit: Int): List<PointEntity>

  /**
   * Delete rows by id (called after a server ACK).
   *
   * Callers MUST chunk `ids`: `IN (:ids)` binds one SQL variable per id and
   * SQLITE_MAX_VARIABLE_NUMBER is 999 on older Android SQLite.
   */
  @Query("DELETE FROM points WHERE id IN (:ids)")
  fun deleteByIds(ids: List<Long>)

  /**
   * Bump the retry counter for rows the server received under a 2xx but did not
   * acknowledge. Same chunking requirement as [deleteByIds].
   */
  @Query("UPDATE points SET attempts = attempts + 1 WHERE id IN (:ids)")
  fun incrementAttempts(ids: List<Long>): Int

  /** Sample of rows that have exhausted their attempts — for the eviction diagnostic. */
  @Query("SELECT id FROM points WHERE attempts >= :max ORDER BY t ASC, id ASC LIMIT :limit")
  fun idsExceedingAttempts(max: Int, limit: Int): List<Long>

  /** Oldest timestamp among exhausted rows (diagnostic context). Null when none. */
  @Query("SELECT MIN(t) FROM points WHERE attempts >= :max")
  fun oldestExceedingAttempts(max: Int): Long?

  /** Evict rows that have exhausted their attempts. Returns the number deleted. */
  @Query("DELETE FROM points WHERE attempts >= :max")
  fun deleteExceedingAttempts(max: Int): Int

  /** Total buffered rows (used by getState.bufferedCount). */
  @Query("SELECT COUNT(*) FROM points")
  fun count(): Int
}
