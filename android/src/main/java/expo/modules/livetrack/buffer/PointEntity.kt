package expo.modules.livetrack.buffer

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
)
