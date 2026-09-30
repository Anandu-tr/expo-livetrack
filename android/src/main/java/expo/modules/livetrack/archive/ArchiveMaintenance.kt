package expo.modules.livetrack.archive

import expo.modules.livetrack.buffer.BufferDb

/** Rolling-window retention for `archived_points`: prune by age, then by row cap. */
internal object ArchiveMaintenance {
  private const val DAY_MS = 86_400_000L

  /** @return (rows pruned by age, rows dropped by the cap). */
  fun run(db: BufferDb, archiveDays: Int, now: Long): Pair<Int, Int> {
    if (archiveDays <= 0) return 0 to 0
    val dao = db.archiveDao()
    val pruned = dao.prune(now - archiveDays * DAY_MS)
    val trimmed = dao.trimToCap(BufferDb.ARCHIVE_ROW_CAP)
    return pruned to trimmed
  }
}
