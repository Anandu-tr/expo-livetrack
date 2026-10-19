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

  /** Sub-brands that run their parent's ROM (POCO and Redmi phones run MIUI/HyperOS). */
  private val BRAND_PARENT = mapOf("poco" to "xiaomi", "redmi" to "xiaomi")

  /** @param manufacturer typically `Build.MANUFACTURER`, case-insensitive. */
  fun candidatesFor(manufacturer: String): List<ComponentName> =
    CANDIDATES[manufacturer.trim().lowercase()] ?: emptyList()

  /** As [candidatesFor], falling back to `Build.BRAND` for sub-brands. */
  fun candidatesFor(manufacturer: String, brand: String): List<ComponentName> =
    candidatesFor(manufacturer).ifEmpty { candidatesFor(BRAND_PARENT[brand.trim().lowercase()] ?: "") }

  fun isKnownAggressiveOem(manufacturer: String): Boolean =
    candidatesFor(manufacturer).isNotEmpty()

  fun isKnownAggressiveOem(manufacturer: String, brand: String): Boolean =
    candidatesFor(manufacturer, brand).isNotEmpty()

  /** Xiaomi, POCO or Redmi: the per-app MIUI battery saver applies. */
  fun isXiaomiFamily(manufacturer: String, brand: String): Boolean =
    manufacturer.trim().lowercase() == "xiaomi" || BRAND_PARENT[brand.trim().lowercase()] == "xiaomi"

  // MIUI/HyperOS per-app "Battery saver" screen (No restrictions / Battery saver /
  // Restrict background). Separate from both Autostart and Android's own
  // battery-optimization exemption, and the setting that decides whether MIUI
  // kills a background tracker. Undocumented, hence best-effort with fallback.
  const val MIUI_BATTERY_SAVER_ACTION = "miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY"
  const val MIUI_BATTERY_SAVER_PACKAGE = "com.miui.powerkeeper"
  const val MIUI_BATTERY_SAVER_CLASS = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
  const val MIUI_EXTRA_PACKAGE_NAME = "package_name"
  const val MIUI_EXTRA_PACKAGE_LABEL = "package_label"
}
