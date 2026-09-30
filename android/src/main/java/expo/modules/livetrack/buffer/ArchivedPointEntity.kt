package expo.modules.livetrack.buffer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A server-acknowledged location row, kept on-device for `archiveDays` so a day
 * can be audited (`getCoverage`) or re-sent (`replay`) if the server lost it.
 *
 * Separate table on purpose: `points` must only ever hold un-uploaded rows
 * (`PointDao.count()` feeds `bufferedCount`, eviction reasons about it). Only
 * location rows are archived — events are one-shot diagnostics.
 *
 * `id` is the ORIGINAL `points.id` (not autoGenerate): `points` is AUTOINCREMENT
 * so ids never recur, and a replay that re-POSTs the original id is idempotent
 * on a server that keys by it. `INSERT OR IGNORE` makes a double ack harmless.
 */
@Entity(
  tableName = "archived_points",
  indices = [Index(value = ["t"]), Index(value = ["userId", "t"])],
)
data class ArchivedPointEntity(
  @PrimaryKey val id: Long,
  val userId: String,
  val t: Long,
  val lat: Double?,
  val lng: Double?,
  val speed: Double,
  val acc: Double,
  val batt: Int,
  val charging: Boolean,
  val act: String?,
  val mock: Boolean,
  /** Wall-clock time the server acknowledged the row. */
  val uploadedAt: Long,
) {
  /** Same wire shape as a live row, so `UploadClient.buildBody` is reused verbatim. */
  fun toPoint(): PointEntity = PointEntity(
    id = id,
    userId = userId,
    t = t,
    lat = lat,
    lng = lng,
    speed = speed,
    acc = acc,
    batt = batt,
    charging = charging,
    act = act,
    mock = mock,
    eventType = null,
    attempts = 0,
  )

  companion object {
    fun from(p: PointEntity, uploadedAt: Long): ArchivedPointEntity = ArchivedPointEntity(
      id = p.id,
      userId = p.userId,
      t = p.t,
      lat = p.lat,
      lng = p.lng,
      speed = p.speed,
      acc = p.acc,
      batt = p.batt,
      charging = p.charging,
      act = p.act,
      mock = p.mock,
      uploadedAt = uploadedAt,
    )
  }
}
