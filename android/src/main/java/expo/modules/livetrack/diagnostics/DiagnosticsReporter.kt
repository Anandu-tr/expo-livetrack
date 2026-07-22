package expo.modules.livetrack.diagnostics

import android.content.SharedPreferences
import expo.modules.livetrack.Prefs

/**
 * Vendor-agnostic sink for operational failures (upload/API + SQL). The plugin
 * core depends only on this interface; the Firebase implementation lives in
 * [CrashlyticsReporter] and is a compileOnly soft dependency.
 */
interface DiagnosticsReporter {
  fun recordFailure(kind: String, message: String, attrs: Map<String, String> = emptyMap())
  fun log(message: String)

  /** Does nothing. Default when diagnostics are off or the SDK is absent. */
  object Noop : DiagnosticsReporter {
    override fun recordFailure(kind: String, message: String, attrs: Map<String, String>) {}
    override fun log(message: String) {}
  }
}

/** Resolves the active reporter from persisted config. Never throws. */
object DiagnosticsReporters {
  fun resolve(prefs: SharedPreferences): DiagnosticsReporter {
    val useCrashlytics = prefs.getBoolean(Prefs.KEY_DIAGNOSTICS_CRASHLYTICS, false)
    if (!useCrashlytics) return DiagnosticsReporter.Noop
    // Instantiating CrashlyticsReporter links FirebaseCrashlytics; if the host app
    // does not ship the SDK this throws NoClassDefFoundError (a Throwable), which
    // runCatching catches -> safe degrade to Noop.
    return runCatching { CrashlyticsReporter() as DiagnosticsReporter }
      .getOrDefault(DiagnosticsReporter.Noop)
  }
}
