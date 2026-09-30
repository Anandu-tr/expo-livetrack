package expo.modules.livetrack.oem

import android.content.ComponentName

/**
 * Best-effort lookup of OEM-specific "autostart"/"protected apps" settings
 * screens. Android gives foreground services no protection against these —
 * they sit entirely outside isIgnoringBatteryOptimizations() and are the
 * #1 field-observed cause of background kills (see
 * docs/2026-05-31-location-tracker-design.md). Component names are
 * undocumented and drift across ROM versions, hence the ordered candidate
 * list per manufacturer: the caller tries each in order and falls back to
 * the generic app-info screen if all fail.
 */
object OemAutostart {

  private val CANDIDATES: Map<String, List<ComponentName>> = mapOf(
    "xiaomi" to listOf(
      ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "oppo" to listOf(
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
      ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
    "vivo" to listOf(
      ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
      ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
      ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity"),
    ),
    "realme" to listOf(
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
      ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
  )

  /** @param manufacturer typically `Build.MANUFACTURER`, case-insensitive. */
  fun candidatesFor(manufacturer: String): List<ComponentName> =
    CANDIDATES[manufacturer.trim().lowercase()] ?: emptyList()

  fun isKnownAggressiveOem(manufacturer: String): Boolean =
    candidatesFor(manufacturer).isNotEmpty()
}
