package expo.modules.livetrack.keepalive

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import expo.modules.livetrack.Prefs
import expo.modules.livetrack.buffer.PointDao
import expo.modules.livetrack.buffer.PointEntity

/**
 * Why and when the app's process died, from Android's own record
 * (`ActivityManager.getHistoricalProcessExitReasons`, Android 11+), written as
 * SERVICE_STOPPED rows at the real time of death on the next start.
 *
 * Nothing of ours runs when the user force-stops the app or the OS/OEM kills it,
 * so these deaths were only visible as silence (or inferred from the next
 * watchdog restart). Force-stop is `r = "force_stop"`; kills are `killed_*`
 * with Android's description appended when it gives one.
 */
internal object ProcessExits {

  // ApplicationExitInfo.REASON_* (API 30+), mirrored so the selection logic runs
  // under plain JUnit.
  const val REASON_UNKNOWN = 0
  const val REASON_EXIT_SELF = 1
  const val REASON_SIGNALED = 2
  const val REASON_LOW_MEMORY = 3
  const val REASON_CRASH = 4
  const val REASON_CRASH_NATIVE = 5
  const val REASON_ANR = 6
  const val REASON_INITIALIZATION_FAILURE = 7
  const val REASON_PERMISSION_CHANGE = 8
  const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
  const val REASON_USER_REQUESTED = 10
  const val REASON_USER_STOPPED = 11
  const val REASON_DEPENDENCY_DIED = 12
  const val REASON_OTHER = 13
  const val REASON_FREEZER = 14
  const val REASON_PACKAGE_STATE_CHANGE = 15
  const val REASON_PACKAGE_UPDATED = 16

  /**
   * RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE. A process running the tracking
   * foreground service (125) or showing the app (100) is at least this important;
   * an idle cached process (400) after sign-out is not, and its death costs nothing.
   */
  private const val IMPORTANCE_PERCEPTIBLE = 230

  const val MAX_DESCRIPTION = 60
  private const val MAX_EXITS = 16

  private val LOCK = Any()

  data class Exit(val timestamp: Long, val reason: Int, val importance: Int, val description: String?)

  /**
   * Exits newer than [watermark] that are worth a row, oldest first, and the new
   * watermark. Force-stop always counts; other deaths only while the process was
   * doing something (tracking or on screen).
   */
  fun select(exits: List<Exit>, watermark: Long): Pair<List<Exit>, Long> {
    val newer = exits.filter { it.timestamp > watermark }.sortedBy { it.timestamp }
    val keep = newer.filter { it.reason == REASON_USER_STOPPED || it.importance <= IMPORTANCE_PERCEPTIBLE }
    return keep to (newer.maxOfOrNull { it.timestamp } ?: watermark)
  }

  /** `r` for a SERVICE_STOPPED row: the cause, plus Android's description if any. */
  fun label(reason: Int, description: String?): String {
    val cause = when (reason) {
      REASON_USER_STOPPED -> "force_stop"
      REASON_LOW_MEMORY -> "killed_low_memory"
      REASON_SIGNALED -> "killed_signal"
      REASON_OTHER -> "killed_other"
      REASON_EXCESSIVE_RESOURCE_USAGE -> "killed_resource_usage"
      REASON_PERMISSION_CHANGE -> "killed_permission_change"
      REASON_USER_REQUESTED -> "killed_user_requested"
      REASON_DEPENDENCY_DIED -> "killed_dependency"
      REASON_FREEZER -> "killed_freezer"
      REASON_PACKAGE_STATE_CHANGE -> "killed_package_state"
      REASON_PACKAGE_UPDATED -> "app_updated"
      REASON_CRASH, REASON_CRASH_NATIVE -> "crash"
      REASON_ANR -> "anr"
      REASON_INITIALIZATION_FAILURE -> "init_failure"
      REASON_EXIT_SELF -> "exit_self"
      else -> "killed_unknown"
    }
    val desc = description?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_DESCRIPTION)
    return if (desc.isNullOrEmpty()) cause else "$cause:$desc"
  }

  /**
   * Record deaths since the last call as SERVICE_STOPPED rows at their real time.
   * The first call only sets the watermark (no backfill of older history).
   * Blocking (DB insert): call off the main thread.
   */
  fun recordNew(context: Context, dao: PointDao) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val am = context.getSystemService(ActivityManager::class.java) ?: return
    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    synchronized(LOCK) {
      val now = System.currentTimeMillis()
      if (!prefs.contains(Prefs.KEY_LAST_EXIT_TS)) {
        prefs.edit().putLong(Prefs.KEY_LAST_EXIT_TS, now).commit()
        return
      }
      val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS) }
        .getOrNull() ?: return
      val exits = infos.map { Exit(it.timestamp, it.reason, it.importance, it.description) }
      val (keep, mark) = select(exits, prefs.getLong(Prefs.KEY_LAST_EXIT_TS, now))
      val userId = prefs.getString(Prefs.KEY_USER_ID, "") ?: ""
      for (e in keep) {
        runCatching {
          dao.insert(
            PointEntity(userId = userId, t = e.timestamp, act = label(e.reason, e.description), eventType = "SERVICE_STOPPED"),
          )
        }
      }
      prefs.edit().putLong(Prefs.KEY_LAST_EXIT_TS, mark).commit()
    }
  }
}
