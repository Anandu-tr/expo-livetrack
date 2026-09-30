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
/// String. Response 2xx: `{ "acceptedIds": ["<rowId>", ...] }` (see `AckParser` for
/// the envelope/field variants tolerated); only rows whose ids come back are
/// deleted — the rest are retried by the next run, up to `maxUploadAttempts`.
final class Uploader {

  /// Attempts a row may sit unacknowledged under repeated 2xx responses before it is
  /// evicted. Mirrors Android's `UploadWorker.DEFAULT_MAX_UPLOAD_ATTEMPTS`.
  static let defaultMaxUploadAttempts = 15

  /// Keeps a diagnostic body snippet small enough to log cheaply.
  private static let bodySnippetMax = 300

  static let shared = Uploader()

  private let buffer = Buffer.shared
  // Serialises sync attempts: inline-on-fix AND background-trigger could overlap and
  // double-POST / double-delete the same rows. This guard keeps one sync in flight.
  private let lock = NSLock()
  private var inFlight = false
  // A replay re-sends archived rows; it never touches `points`, so it cannot
  // double-delete, but two streams to the server at once is what we avoid.
  private var replayInFlight = false

  // Stands in for WorkManager's exponential backoff, which iOS has no equivalent of.
  // `sync()` is nudged on every fix (~12 s while moving), so without this a server
  // that 2xx's while acknowledging nothing would be hammered once per fix and burn
  // through the whole attempt budget in minutes instead of hours.
  private var consecutiveZeroAcks = 0
  private var backoffUntil: TimeInterval = 0

  /// Best-effort callback for emitting onSyncError to JS (set by the module).
  /// (message, status?, bufferedCount)
  var onSyncError: ((String, Int?, Int) -> Void)?

  private init() {}

  /// Read up to `batchSize` rows, POST them, and on a 2xx delete the accepted ids.
  /// Network I/O is async via URLSession; the whole call returns immediately.
  func sync(
    url: String, token: String, batchSize: Int,
    maxAttempts: Int = Uploader.defaultMaxUploadAttempts, archiveDays: Int = 0
  ) {
    guard !url.isEmpty, !token.isEmpty else { return }

    lock.lock()
    if inFlight || replayInFlight || Date().timeIntervalSince1970 < backoffUntil {
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
        self.handleAccepted(
          data: data, status: status, sentRows: rows,
          host: requestURL.host ?? "?", maxAttempts: maxAttempts, archiveDays: archiveDays
        )
      } else {
        // Non-2xx: emit best-effort error, leave rows for retry. Never counts
        // against the attempt budget — 4xx/5xx are retried losslessly.
        self.emitError("server error (\(status))", status: status)
      }
    }
    task.resume()
  }

  // MARK: - ACK handling

  /// Classify a 2xx response and reconcile the buffer against it. Mirrors Android's
  /// `UploadWorker.handleAccepted` — keep the two in step.
  ///
  /// WHY THIS EXISTS: a 2xx that acknowledged NOTHING used to be treated as full
  /// success — no delete, no counter, no signal — so devices re-POSTed the same
  /// batch indefinitely with zero observability.
  private func handleAccepted(
    data: Data?, status: Int, sentRows: [Buffer.Row], host: String, maxAttempts: Int, archiveDays: Int
  ) {
    let sentIds = sentRows.map { $0.id }
    let parsed = AckParser.parse(data)
    let sentSet = Set(sentIds)
    // Only ever delete ids we actually sent. A server echoing its OWN primary keys
    // instead of our row ids must not delete an unrelated buffered row by
    // coincidence — that would be silent data loss.
    let accepted = parsed.filter { sentSet.contains($0) }
    let acceptedSet = Set(accepted)
    let unacked = sentIds.filter { !acceptedSet.contains($0) }

    // Acked rows leave the buffer; with the archive on they are copied first, in
    // the same transaction. Chunked to keep every IN (...) under the variable limit.
    let acceptedRows = sentRows.filter { acceptedSet.contains($0.id) }
    if !acceptedRows.isEmpty {
      for start in stride(from: 0, to: acceptedRows.count, by: 500) {
        let chunk = Array(acceptedRows[start..<min(start + 500, acceptedRows.count)])
        buffer.archiveAndDelete(rows: chunk, archive: archiveDays > 0)
      }
      if archiveDays > 0 { maintainArchive(days: archiveDays) }
    }

    if accepted.isEmpty {
      // Fires on attempt #1, hours before any eviction.
      LiveTrackDiagnostics.recordFailure(
        parsed.isEmpty ? "upload-ack-empty" : "upload-ack-foreign",
        parsed.isEmpty
          ? "2xx with zero parseable acceptedIds"
          : "2xx acked \(parsed.count) ids, none of them ours",
        [
          "status": String(status),
          "host": host,
          "rowsSent": String(sentIds.count),
          "parsedCount": String(parsed.count),
          "sampleSent": sentIds.prefix(3).map { String($0) }.joined(separator: ","),
          "sampleParsed": parsed.prefix(3).map { String($0) }.joined(separator: ","),
          "bodyLen": String(data?.count ?? 0),
          "bodySnippet": Uploader.snippet(data),
          "bufferedCount": String(buffer.count()),
        ]
      )
    }

    if !unacked.isEmpty {
      buffer.incrementAttempts(unacked)
      evictExhausted(maxAttempts: maxAttempts)
    }

    noteAckOutcome(gotAcks: !accepted.isEmpty)
  }

  /// Bounded, logged eviction — the ONLY path that drops a buffered row without a
  /// server ACK. Reached only for rows declined `maxAttempts` separate times under a 2xx.
  private func evictExhausted(maxAttempts: Int) {
    let sample = buffer.idsExceedingAttempts(maxAttempts, limit: 20)
    guard !sample.isEmpty else { return }
    let oldestT = buffer.oldestExceedingAttempts(maxAttempts)
    let evicted = buffer.deleteExceedingAttempts(maxAttempts)
    guard evicted > 0 else { return }
    LiveTrackDiagnostics.recordFailure(
      "buffer-evicted",
      "dropped \(evicted) rows after \(maxAttempts) unacknowledged 2xx uploads",
      [
        "evictedCount": String(evicted),
        "maxAttempts": String(maxAttempts),
        "sampleIds": sample.prefix(10).map { String($0) }.joined(separator: ","),
        "oldestT": oldestT.map { String($0) } ?? "?",
      ]
    )
    emitError("dropped \(evicted) buffered rows after \(maxAttempts) failed uploads", status: nil)
  }

  /// Advance or reset the zero-ack backoff. 30 s doubling to a 15 min ceiling, which
  /// puts the 15-attempt budget at roughly an hour — matching the Android schedule.
  private func noteAckOutcome(gotAcks: Bool) {
    lock.lock()
    defer { lock.unlock() }
    if gotAcks {
      consecutiveZeroAcks = 0
      backoffUntil = 0
      return
    }
    consecutiveZeroAcks += 1
    let delay = min(30 * pow(2.0, Double(min(consecutiveZeroAcks, 5))), 900)
    backoffUntil = Date().timeIntervalSince1970 + delay
  }

  /// Collapsed + truncated: an HTML error page from a proxy would otherwise flood the log.
  private static func snippet(_ data: Data?) -> String {
    guard let data = data, let text = String(data: data, encoding: .utf8) else { return "" }
    let collapsed = text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    return String(collapsed.prefix(bodySnippetMax))
  }

  private func clearInFlight() {
    lock.lock()
    inFlight = false
    lock.unlock()
  }

  // MARK: - Archive retention + replay

  /// Rolling-window retention: prune by age, then by row cap. iOS has no periodic
  /// worker, so this runs on start and after every acked upload.
  func maintainArchive(days: Int) {
    guard days > 0 else { return }
    let cutoff = Int64(Date().timeIntervalSince1970 * 1000) - Int64(days) * 86_400_000
    buffer.pruneArchive(before: cutoff)
    buffer.trimArchive(cap: Buffer.archiveRowCap)
  }

  struct ReplayOutcome {
    let sent: Int
    let accepted: Int
    let batches: Int
    let failedBatches: Int
  }

  enum ReplayError: Error {
    case busy
    case auth
  }

  private static let replayPacingSeconds = 0.25
  private static let replayMaxConsecutiveFailures = 3

  /// Re-send archived rows with their ORIGINAL ids and times. Mirrors Android's
  /// `ReplayRunner`: one replay at a time, paced, stops after 3 consecutive
  /// failures, 401 → `.auth` (the JS side refreshes the token and retries).
  func replay(
    url: String, token: String, rows: [Buffer.Row], batchSize: Int,
    completion: @escaping (Result<ReplayOutcome, Error>) -> Void
  ) {
    lock.lock()
    if replayInFlight {
      lock.unlock()
      completion(.failure(ReplayError.busy))
      return
    }
    replayInFlight = true
    lock.unlock()

    guard let requestURL = URL(string: url) else {
      lock.lock(); replayInFlight = false; lock.unlock()
      completion(.failure(NSError(domain: "livetrack", code: 1, userInfo: [NSLocalizedDescriptionKey: "bad url"])))
      return
    }

    DispatchQueue.global(qos: .utility).async { [self] in
      var bgTaskId: UIBackgroundTaskIdentifier = .invalid
      bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "livetrack.replay") {
        if bgTaskId != .invalid {
          UIApplication.shared.endBackgroundTask(bgTaskId)
          bgTaskId = .invalid
        }
      }
      defer {
        lock.lock(); replayInFlight = false; lock.unlock()
        if bgTaskId != .invalid {
          UIApplication.shared.endBackgroundTask(bgTaskId)
          bgTaskId = .invalid
        }
      }

      let size = min(max(batchSize, 1), 500)
      var accepted = 0
      var batches = 0
      var failed = 0
      var consecutive = 0

      for start in stride(from: 0, to: rows.count, by: size) {
        let chunk = Array(rows[start..<min(start + size, rows.count)])
        batches += 1
        guard let body = try? buildBody(rows: chunk) else {
          failed += 1
          consecutive += 1
          if consecutive >= Uploader.replayMaxConsecutiveFailures { break }
          continue
        }
        let (status, data) = postSync(url: requestURL, token: token, body: body)
        if status == 401 {
          completion(.failure(ReplayError.auth))
          return
        }
        if (200...299).contains(status) {
          let ids = Set(chunk.map { $0.id })
          accepted += AckParser.parse(data).filter { ids.contains($0) }.count
          consecutive = 0
        } else {
          failed += 1
          consecutive += 1
          if consecutive >= Uploader.replayMaxConsecutiveFailures { break }
        }
        Thread.sleep(forTimeInterval: Uploader.replayPacingSeconds)
      }
      completion(.success(ReplayOutcome(sent: rows.count, accepted: accepted, batches: batches, failedBatches: failed)))
    }
  }

  /// Blocking POST used by the replay loop (runs on a utility queue, never main).
  private func postSync(url: URL, token: String, body: Data) -> (Int, Data?) {
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = 30
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    request.httpBody = body

    let semaphore = DispatchSemaphore(value: 0)
    var status = -1
    var payload: Data?
    let task = URLSession.shared.dataTask(with: request) { data, response, _ in
      status = (response as? HTTPURLResponse)?.statusCode ?? -1
      payload = data
      semaphore.signal()
    }
    task.resume()
    _ = semaphore.wait(timeout: .now() + 45)
    return (status, payload)
  }

  // MARK: - Body building

  func buildBody(rows: [Buffer.Row]) throws -> Data {
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
        // Lifecycle events carry their reason (relaunch/stop/...) in `act`.
        if let reason = r.act { e["r"] = reason }
        events.append(e)
      }
    }

    let payload: [String: Any] = ["points": points, "events": events]
    // VERIFY: JSONSerialization renders Int64 `t` as a plain number (it does via NSNumber).
    return try JSONSerialization.data(withJSONObject: payload, options: [])
  }

  // MARK: - Error emission

  private func emitError(_ message: String, status: Int?) {
    let bufferedCount = buffer.count()
    // Best-effort: dropped if no JS module is attached (data is still buffered).
    onSyncError?(message, status, bufferedCount)
  }
}
