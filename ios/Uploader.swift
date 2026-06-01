import Foundation
import UIKit

/// Flushes buffered rows to the server (the wire contract mirrored by the
/// in-package test-server + the Android `UploadWorker`).
///
/// Body:
/// ```json
/// { "points": [ {"id","u","l","g","t","s","acc","b","c","act","mock"} ],
///   "events": [ {"id","u","e","t","l?","g?","b?"} ] }
/// ```
/// A buffer row is a location when `eventType == nil` (goes in `points`), else an
/// event (goes in `events`, `e = eventType`). Each `id` is the local row id as a
/// String. Response 200: `{ "acceptedIds": ["<rowId>", ...] }`; only rows whose
/// ids come back are deleted — the rest are retried by the next run.
final class Uploader {

  static let shared = Uploader()

  private let buffer = Buffer.shared
  // Serialises sync attempts: inline-on-fix AND background-trigger could overlap and
  // double-POST / double-delete the same rows. This guard keeps one sync in flight.
  private let lock = NSLock()
  private var inFlight = false

  /// Best-effort callback for emitting onSyncError to JS (set by the module).
  /// (message, status?, bufferedCount)
  var onSyncError: ((String, Int?, Int) -> Void)?

  private init() {}

  /// Read up to `batchSize` rows, POST them, and on a 2xx delete the accepted ids.
  /// Network I/O is async via URLSession; the whole call returns immediately.
  func sync(url: String, token: String, batchSize: Int) {
    guard !url.isEmpty, !token.isEmpty else { return }

    lock.lock()
    if inFlight {
      lock.unlock()
      return
    }
    inFlight = true
    lock.unlock()

    let rows = buffer.unsynced(limit: batchSize)
    if rows.isEmpty {
      clearInFlight()
      return
    }

    guard let requestURL = URL(string: url) else {
      clearInFlight()
      return
    }

    let body: Data
    do {
      body = try buildBody(rows: rows)
    } catch {
      clearInFlight()
      return
    }

    var request = URLRequest(url: requestURL)
    request.httpMethod = "POST"
    request.timeoutInterval = 30
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    request.httpBody = body

    // Request extra background execution time so a fix delivered while backgrounded
    // can finish its upload before iOS suspends us. beginBackgroundTask needs no
    // Info.plist entry (unlike BGTaskScheduler). // VERIFY: beginBackgroundTask /
    // endBackgroundTask + UIBackgroundTaskIdentifier.invalid sentinel.
    var bgTaskId: UIBackgroundTaskIdentifier = .invalid
    bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "livetrack.upload") {
      // Expiration handler: end the task if iOS reclaims time before we finish.
      if bgTaskId != .invalid {
        UIApplication.shared.endBackgroundTask(bgTaskId)
        bgTaskId = .invalid
      }
    }
    let endBgTask = {
      if bgTaskId != .invalid {
        UIApplication.shared.endBackgroundTask(bgTaskId)
        bgTaskId = .invalid
      }
    }

    // VERIFY: URLSession.shared.dataTask completion runs on a background queue.
    let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
      guard let self = self else { endBgTask(); return }
      defer {
        self.clearInFlight()
        endBgTask()
      }

      if let error = error {
        self.emitError(error.localizedDescription, status: nil)
        return // leave rows for retry
      }

      let status = (response as? HTTPURLResponse)?.statusCode ?? -1

      if (200...299).contains(status) {
        let acceptedIds = self.parseAcceptedIds(data)
        if !acceptedIds.isEmpty {
          self.buffer.deleteByIds(acceptedIds)
        }
      } else {
        // Non-2xx: emit best-effort error, leave rows for retry.
        self.emitError("server error (\(status))", status: status)
      }
    }
    task.resume()
  }

  private func clearInFlight() {
    lock.lock()
    inFlight = false
    lock.unlock()
  }

  // MARK: - Body building

  private func buildBody(rows: [Buffer.Row]) throws -> Data {
    var points: [[String: Any]] = []
    var events: [[String: Any]] = []

    for r in rows {
      if r.eventType == nil {
        // (lat/lng are always present in practice: the on-device accuracy filter
        // means only valid fixes are buffered. The NSNull fallbacks keep the schema
        // honest if a null ever slips through.)
        let p: [String: Any] = [
          "id": String(r.id),
          "u": r.userId,
          "l": r.lat ?? NSNull(),
          "g": r.lng ?? NSNull(),
          "t": r.t,
          "s": r.speed,
          "acc": r.acc,
          "b": r.batt,
          "c": r.charging,
          "act": r.act ?? "UNKNOWN",
          "mock": r.mock,
        ]
        points.append(p)
      } else {
        var e: [String: Any] = [
          "id": String(r.id),
          "u": r.userId,
          "e": r.eventType ?? "",
          "t": r.t,
        ]
        if let lat = r.lat { e["l"] = lat }
        if let lng = r.lng { e["g"] = lng }
        if r.batt >= 0 { e["b"] = r.batt }
        events.append(e)
      }
    }

    let payload: [String: Any] = ["points": points, "events": events]
    // VERIFY: JSONSerialization renders Int64 `t` as a plain number (it does via NSNumber).
    return try JSONSerialization.data(withJSONObject: payload, options: [])
  }

  private func parseAcceptedIds(_ data: Data?) -> [Int64] {
    guard let data = data else { return [] }
    guard let obj = try? JSONSerialization.jsonObject(with: data, options: []),
          let dict = obj as? [String: Any],
          let arr = dict["acceptedIds"] as? [Any] else {
      return []
    }
    // ids are sent as Strings; tolerate numbers too.
    return arr.compactMap { value -> Int64? in
      if let s = value as? String { return Int64(s) }
      if let n = value as? NSNumber { return n.int64Value }
      return nil
    }
  }

  // MARK: - Error emission

  private func emitError(_ message: String, status: Int?) {
    let bufferedCount = buffer.count()
    // Best-effort: dropped if no JS module is attached (data is still buffered).
    onSyncError?(message, status, bufferedCount)
  }
}
