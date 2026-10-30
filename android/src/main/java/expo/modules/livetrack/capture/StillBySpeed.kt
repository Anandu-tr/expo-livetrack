package expo.modules.livetrack.capture

/**
 * The still decision while Activity Recognition is silent: a run of fixes
 * reporting ~0 speed for [RUN_MS] means the phone is parked.
 *
 * Fixes without speed say nothing either way, and network fixes in a vehicle
 * often have none. Production (Oct 30, 12:13): stopped, then off in a vehicle on
 * such fixes; accepted moves of 626 m and 982 m went unnoticed and the phone
 * went still mid-ride. An accepted move at vehicle speed now ends the run.
 * Fixes the gate rejected for landing far away don't start one either: on
 * Oct 29-30 such fixes, reporting 0 mid-ride, put the phone in still mode
 * again and again while it drove on.
 *
 * Pure; the service feeds it from its main looper. Times are elapsedRealtime.
 */
class StillBySpeed {
  private var since = 0L

  /** A usable fix's reported speed; [far]: the gate rejected it for landing far away. */
  fun onSpeed(speedMps: Double?, now: Long, far: Boolean = false) {
    if (speedMps == null) return
    if (speedMps >= LocationGate.STILL_SPEED_MPS) since = 0L else if (since == 0L && !far) since = now
  }

  /** An accepted fix [displacementM] from the previous one, [dtMs] later. */
  fun onMove(displacementM: Double, dtMs: Long, now: Long) {
    if (dtMs <= 0L || displacementM < MOVE_MIN_M) return
    if (displacementM / (dtMs / 1000.0) >= LocationGate.MOTION_SPEED_MPS) since = 0L
  }

  fun due(now: Long): Boolean = since != 0L && now - since >= RUN_MS

  fun reset() {
    since = 0L
  }

  companion object {
    /** This long of fixes reporting ~0 speed counts as still. */
    const val RUN_MS = 3 * 60_000L

    /** Moves shorter than this are drift, not travel, whatever their speed. */
    const val MOVE_MIN_M = 50.0
  }
}
