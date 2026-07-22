package expo.modules.livetrack.diagnostics

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Reports failures to Firebase Crashlytics. Crashlytics auto-initializes from the
 * host app's google-services.json. Every call is exception-safe so a misconfigured
 * host never affects the capture/upload path.
 *
 * Firebase Crashlytics is a compileOnly dependency of the module — if the host app
 * does not ship the SDK, this class fails to link and [DiagnosticsReporters.resolve]
 * falls back to [DiagnosticsReporter.Noop].
 */
internal class CrashlyticsReporter : DiagnosticsReporter {
  private val crashlytics = FirebaseCrashlytics.getInstance()

  override fun recordFailure(kind: String, message: String, attrs: Map<String, String>) {
    runCatching {
      crashlytics.setCustomKey("livetrack_kind", kind)
      attrs.forEach { (k, v) -> crashlytics.setCustomKey("livetrack_$k", v) }
      crashlytics.log("livetrack:$kind $message")
      crashlytics.recordException(LiveTrackDiagnosticException(kind, message))
    }
  }

  override fun log(message: String) {
    runCatching { crashlytics.log(message) }
  }
}

/** Carrier exception so Crashlytics groups non-fatals by kind. */
internal class LiveTrackDiagnosticException(kind: String, message: String) :
  Exception("[$kind] $message")
