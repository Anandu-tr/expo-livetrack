package expo.modules.livetrack.buffer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single buffered row in the `points` table.
 *
 * One table holds BOTH location samples and tier-2 events:
 *  - `eventType == null`  -> a location row (lat/lng/acc/speed populated).
 *  - `eventType != null`  -> a diagnostic/event row (e.g. "HEARTBEAT"); lat/lng
 *                            may carry the last known position for context.
 *
 * Rows are deleted on server ACK (see [PointDao.deleteByIds]); there is no
 * `synced` column by design — the buffer only ever holds un-uploaded rows.
 *
 * The one exception to "ACK or keep forever" is [attempts]: a row the server
 * repeatedly refuses to acknowledge is eventually evicted, because the alternative
 * (observed in production) is a device re-uploading the same batch indefinitely.
 *
 * NOTE: `acc` defaults to -1.0 (not null) so an "unknown accuracy" is still a
 * concrete sentinel; the capture path ALWAYS writes the real `location.accuracy`,
 * which fixes the production acc=null bug.
 */
@Entity(tableName = "points")
data class PointEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String,
  val t: Long,
  val lat: Double? = null,
  val lng: Double? = null,
  val speed: Double = 0.0,
  val acc: Double = -1.0,
  val batt: Int = -1,
  val charging: Boolean = false,
  val act: String? = null,
  val mock: Boolean = false,
  val eventType: String? = null,
  /**
   * Upload attempts where this row was SENT and the server answered 2xx without
   * acknowledging its id. Bumped on that exact signal only — never on network
   * errors, 4xx or 5xx, which are retried losslessly. At
   * `UploadWorker.DEFAULT_MAX_UPLOAD_ATTEMPTS` the row is evicted and reported,
   * which is what makes an infinite re-upload loop impossible.
   *
   * `defaultValue` is required, not cosmetic: Room validates the migrated
   * TableInfo against this entity, so the fresh-install CREATE TABLE must match
   * what `MIGRATION_1_2`'s ALTER TABLE produces.
   */
  @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)
