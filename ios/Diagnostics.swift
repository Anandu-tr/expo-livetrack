import Foundation
import os

/// Vendor-neutral operational sink, mirroring Android's `DiagnosticsReporter`.
///
/// WHY: iOS has no diagnostics plumbing today (`DiagnosticsReporters` /
/// `CrashlyticsReporter` are Android-only and `LiveTrackModule.swift` never reads
/// `config["diagnostics"]`). Upload failures are emitted to JS via `onSyncError`,
/// which is dropped whenever the JS runtime is not attached — precisely the case
/// during background upload, so the failure that matters most is the one nobody
/// sees. Writing to the unified log makes it visible in Console.app / a sysdiagnose
/// regardless.
///
/// SCOPE: this deliberately does NOT reach Crashlytics. Full reporter parity with
/// Android is a separate slice (it needs `config["diagnostics"]` plumbing plus a
/// soft dependency, which CocoaPods does not model as cleanly as Gradle's
/// `compileOnly`).
///
/// // VERIFY: os.Logger requires iOS 14+; the pod's deployment floor is 15.1.
enum LiveTrackDiagnostics {
  private static let logger = Logger(subsystem: "expo.modules.livetrack", category: "diagnostics")

  /// `kind` mirrors the Android failure kinds ("upload-ack-empty",
  /// "upload-ack-foreign", "buffer-evicted", …) so both platforms group alike.
  static func recordFailure(_ kind: String, _ message: String, _ attrs: [String: String] = [:]) {
    let rendered = attrs.sorted { $0.key < $1.key }
      .map { "\($0.key)=\($0.value)" }
      .joined(separator: " ")
    logger.error("livetrack:\(kind, privacy: .public) \(message, privacy: .public) \(rendered, privacy: .public)")
  }
}
