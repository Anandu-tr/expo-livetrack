import Foundation
import SQLite3

/// On-device durable buffer backed by SQLite (the `sqlite3` C API — no extra pod).
///
/// One table `points` holds BOTH location samples and tier-2 events, mirroring the
/// Android Room schema (`PointEntity`):
///  - `eventType == nil` -> a location row (lat/lng/acc/speed populated).
///  - `eventType != nil` -> a diagnostic/event row (e.g. "HEARTBEAT"); lat/lng may
///                          carry the last known position for context.
///
/// Rows are deleted only on server ACK (see `deleteByIds`); there is no `synced`
/// column by design — the buffer only ever holds un-uploaded rows.
///
/// All access is funnelled through a single serial `DispatchQueue` so the type is
/// thread-safe. Reads/`count()` run synchronously; writes run synchronously too so
/// the inserted row is durable before the caller nudges the uploader.
final class Buffer {

  /// A single buffered row, as read back for upload.
  struct Row {
    let id: Int64
    let userId: String
    let t: Int64          // epoch milliseconds
    let lat: Double?
    let lng: Double?
    let speed: Double
    let acc: Double
    let batt: Int
    let charging: Bool
    let act: String?
    let mock: Bool
    let eventType: String?
    /// Upload attempts where this row was SENT and the server answered 2xx without
    /// acknowledging its id. Mirrors Android's `PointEntity.attempts`.
    let attempts: Int
  }

  static let shared = Buffer()

  private let queue = DispatchQueue(label: "expo.modules.livetrack.buffer")
  private var db: OpaquePointer?

  // SQLite wants a destructor telling it to COPY the bound bytes. SQLITE_TRANSIENT
  // (-1) forces a copy; SQLITE_STATIC would (incorrectly) assume the Swift String's
  // buffer outlives the call and leads to garbage/corruption.
  // VERIFY: SQLITE_TRANSIENT bit-cast incantation (standard Swift<->sqlite3 pattern).
  private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

  private init() {
    queue.sync { openAndMigrate() }
  }

  deinit {
    if let db = db { sqlite3_close(db) }
  }

  // MARK: - Setup

  private func dbURL() -> URL {
    let fm = FileManager.default
    // Application Support is not guaranteed to exist; create it (+ intermediates).
    // VERIFY: FileManager.urls(.applicationSupportDirectory) availability/behaviour.
    let baseDir = (try? fm.url(
      for: .applicationSupportDirectory,
      in: .userDomainMask,
      appropriateFor: nil,
      create: true
    )) ?? fm.temporaryDirectory
    let dir = baseDir.appendingPathComponent("expo-livetrack", isDirectory: true)
    if !fm.fileExists(atPath: dir.path) {
      try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    return dir.appendingPathComponent("livetrack.sqlite")
  }

  /// Opens the DB (creating the file/table on first run). Must run on `queue`.
  private func openAndMigrate() {
    let path = dbURL().path
    // VERIFY: sqlite3_open returns SQLITE_OK and populates `db`.
    if sqlite3_open(path, &db) != SQLITE_OK {
      // Couldn't open — leave `db` nil; all ops below no-op safely.
      db = nil
      return
    }
    let createSQL = """
      CREATE TABLE IF NOT EXISTS points (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        userId TEXT,
        t INTEGER,
        lat REAL,
        lng REAL,
        speed REAL,
        acc REAL,
        batt INTEGER,
        charging INTEGER,
        act TEXT,
        mock INTEGER,
        eventType TEXT,
        attempts INTEGER NOT NULL DEFAULT 0
      );
      """
    // VERIFY: sqlite3_exec signature (db, sql, callback, arg, errmsg).
    sqlite3_exec(db, createSQL, nil, nil, nil)

    // v1 -> v2, mirroring Room's MIGRATION_1_2. Additive only, so every un-uploaded
    // row survives the app update. Guarded by a column probe rather than by firing
    // the ALTER and swallowing sqlite's "duplicate column name" error, so it is a
    // clean no-op both on an already-migrated DB and on a fresh install (where the
    // CREATE above already added the column).
    if !hasColumn("attempts", in: "points") {
      sqlite3_exec(db, "ALTER TABLE points ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;", nil, nil, nil)
    }
  }

  /// MUST be called on `queue`. // VERIFY: PRAGMA table_info column 1 is the name.
  private func hasColumn(_ column: String, in table: String) -> Bool {
    guard let db = db else { return false }
    var stmt: OpaquePointer?
    guard sqlite3_prepare_v2(db, "PRAGMA table_info(\(table));", -1, &stmt, nil) == SQLITE_OK else {
      sqlite3_finalize(stmt)
      return false
    }
    defer { sqlite3_finalize(stmt) }
    while sqlite3_step(stmt) == SQLITE_ROW {
      if let name = sqlite3_column_text(stmt, 1), String(cString: name) == column { return true }
    }
    return false
  }

  // MARK: - Inserts

  /// Insert a location row (eventType == nil). Runs synchronously on `queue`.
  func insertLocation(
    userId: String,
    t: Int64,
    lat: Double,
    lng: Double,
    speed: Double,
    acc: Double,
    batt: Int,
    charging: Bool,
    act: String?,
    mock: Bool
  ) {
    queue.sync {
      insertRow(
        userId: userId, t: t, lat: lat, lng: lng, speed: speed, acc: acc,
        batt: batt, charging: charging, act: act, mock: mock, eventType: nil
      )
    }
  }

  /// Insert a tier-2 event row (eventType != nil). lat/lng/batt may carry context.
  func insertEvent(type: String, lastLat: Double?, lastLng: Double?, batt: Int) {
    let userId = TrackingConfig.load()?.userId ?? ""
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    queue.sync {
      insertRow(
        userId: userId, t: now, lat: lastLat, lng: lastLng, speed: 0, acc: -1,
        batt: batt, charging: false, act: nil, mock: false, eventType: type
      )
    }
  }

  /// Core insert. MUST be called on `queue`.
  private func insertRow(
    userId: String,
    t: Int64,
    lat: Double?,
    lng: Double?,
    speed: Double,
    acc: Double,
    batt: Int,
    charging: Bool,
    act: String?,
    mock: Bool,
    eventType: String?
  ) {
    guard let db = db else { return }
    let sql = """
      INSERT INTO points
        (userId, t, lat, lng, speed, acc, batt, charging, act, mock, eventType)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
      """
    var stmt: OpaquePointer?
    // VERIFY: sqlite3_prepare_v2 signature + nByte=-1 (read to NUL).
    guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
      sqlite3_finalize(stmt)
      return
    }
    defer { sqlite3_finalize(stmt) }

    // 1-based bind indices. // VERIFY: sqlite3_bind_* index base is 1.
    sqlite3_bind_text(stmt, 1, userId, -1, SQLITE_TRANSIENT)
    sqlite3_bind_int64(stmt, 2, t)
    bindOptionalDouble(stmt, 3, lat)
    bindOptionalDouble(stmt, 4, lng)
    sqlite3_bind_double(stmt, 5, speed)
    sqlite3_bind_double(stmt, 6, acc)
    sqlite3_bind_int(stmt, 7, Int32(batt))
    sqlite3_bind_int(stmt, 8, charging ? 1 : 0)
    bindOptionalText(stmt, 9, act)
    sqlite3_bind_int(stmt, 10, mock ? 1 : 0)
    bindOptionalText(stmt, 11, eventType)

    sqlite3_step(stmt)
  }

  private func bindOptionalDouble(_ stmt: OpaquePointer?, _ idx: Int32, _ value: Double?) {
    if let value = value {
      sqlite3_bind_double(stmt, idx, value)
    } else {
      sqlite3_bind_null(stmt, idx)
    }
  }

  private func bindOptionalText(_ stmt: OpaquePointer?, _ idx: Int32, _ value: String?) {
    if let value = value {
      sqlite3_bind_text(stmt, idx, value, -1, SQLITE_TRANSIENT)
    } else {
      sqlite3_bind_null(stmt, idx)
    }
  }

  // MARK: - Reads

  /// Oldest-first page of buffered rows for the uploader. Runs synchronously.
  func unsynced(limit: Int) -> [Row] {
    return queue.sync {
      guard let db = db else { return [] }
      var rows: [Row] = []
      let sql = "SELECT id, userId, t, lat, lng, speed, acc, batt, charging, act, mock, eventType, attempts FROM points ORDER BY t ASC, id ASC LIMIT ?;"
      var stmt: OpaquePointer?
      guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
        sqlite3_finalize(stmt)
        return []
      }
      defer { sqlite3_finalize(stmt) }
      sqlite3_bind_int(stmt, 1, Int32(limit))

      // VERIFY: sqlite3_step returns SQLITE_ROW per row, SQLITE_DONE at end.
      while sqlite3_step(stmt) == SQLITE_ROW {
        rows.append(readRow(stmt))
      }
      return rows
    }
  }

  /// MUST be called on `queue` (statement already stepped to a row).
  private func readRow(_ stmt: OpaquePointer?) -> Row {
    let id = sqlite3_column_int64(stmt, 0)
    let userId = columnText(stmt, 1) ?? ""
    let t = sqlite3_column_int64(stmt, 2)
    let lat = columnDouble(stmt, 3)
    let lng = columnDouble(stmt, 4)
    let speed = sqlite3_column_double(stmt, 5)
    let acc = sqlite3_column_double(stmt, 6)
    let batt = Int(sqlite3_column_int(stmt, 7))
    let charging = sqlite3_column_int(stmt, 8) != 0
    let act = columnText(stmt, 9)
    let mock = sqlite3_column_int(stmt, 10) != 0
    let eventType = columnText(stmt, 11)
    let attempts = Int(sqlite3_column_int(stmt, 12))
    return Row(
      id: id, userId: userId, t: t, lat: lat, lng: lng, speed: speed, acc: acc,
      batt: batt, charging: charging, act: act, mock: mock, eventType: eventType,
      attempts: attempts
    )
  }

  private func columnDouble(_ stmt: OpaquePointer?, _ idx: Int32) -> Double? {
    // VERIFY: sqlite3_column_type / SQLITE_NULL comparison.
    if sqlite3_column_type(stmt, idx) == SQLITE_NULL { return nil }
    return sqlite3_column_double(stmt, idx)
  }

  private func columnText(_ stmt: OpaquePointer?, _ idx: Int32) -> String? {
    if sqlite3_column_type(stmt, idx) == SQLITE_NULL { return nil }
    guard let cString = sqlite3_column_text(stmt, idx) else { return nil }
    return String(cString: cString)
  }

  /// Total buffered rows (used by getState.bufferedCount). Runs synchronously.
  func count() -> Int {
    return queue.sync {
      guard let db = db else { return 0 }
      var stmt: OpaquePointer?
      guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM points;", -1, &stmt, nil) == SQLITE_OK else {
        sqlite3_finalize(stmt)
        return 0
      }
      defer { sqlite3_finalize(stmt) }
      if sqlite3_step(stmt) == SQLITE_ROW {
        return Int(sqlite3_column_int64(stmt, 0))
      }
      return 0
    }
  }

  // MARK: - Deletes and retry accounting

  /// Conservative bound for a parameterised `IN (...)` list: SQLITE_MAX_VARIABLE_NUMBER
  /// is 999 on older sqlite builds and `batchSize` is host-supplied.
  private static let sqlVarChunk = 500

  /// Delete rows by id (called after a server ACK). Runs synchronously.
  func deleteByIds(_ ids: [Int64]) {
    runChunked(ids) { "DELETE FROM points WHERE id IN (\($0));" }
  }

  /// Bump the retry counter for rows the server received under a 2xx but did not
  /// acknowledge. Mirrors Android's `PointDao.incrementAttempts`.
  func incrementAttempts(_ ids: [Int64]) {
    runChunked(ids) { "UPDATE points SET attempts = attempts + 1 WHERE id IN (\($0));" }
  }

  /// Runs `sqlBuilder` once per chunk of at most `sqlVarChunk` ids, passing the
  /// placeholder list. Keeps every `IN (...)` statement under the variable limit.
  private func runChunked(_ ids: [Int64], _ sqlBuilder: (String) -> String) {
    guard !ids.isEmpty else { return }
    queue.sync {
      guard let db = db else { return }
      for start in stride(from: 0, to: ids.count, by: Buffer.sqlVarChunk) {
        let chunk = Array(ids[start..<min(start + Buffer.sqlVarChunk, ids.count)])
        // Parameterised IN (...) list — avoids SQL injection / quoting issues.
        let placeholders = Array(repeating: "?", count: chunk.count).joined(separator: ",")
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sqlBuilder(placeholders), -1, &stmt, nil) == SQLITE_OK else {
          sqlite3_finalize(stmt)
          continue
        }
        for (i, id) in chunk.enumerated() {
          sqlite3_bind_int64(stmt, Int32(i + 1), id)
        }
        sqlite3_step(stmt)
        sqlite3_finalize(stmt)
      }
    }
  }

  /// Sample of rows that have exhausted their attempts — for the eviction diagnostic.
  func idsExceedingAttempts(_ max: Int, limit: Int) -> [Int64] {
    return queue.sync {
      guard let db = db else { return [] }
      var out: [Int64] = []
      let sql = "SELECT id FROM points WHERE attempts >= ? ORDER BY t ASC, id ASC LIMIT ?;"
      var stmt: OpaquePointer?
      guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
        sqlite3_finalize(stmt)
        return []
      }
      defer { sqlite3_finalize(stmt) }
      sqlite3_bind_int(stmt, 1, Int32(max))
      sqlite3_bind_int(stmt, 2, Int32(limit))
      while sqlite3_step(stmt) == SQLITE_ROW {
        out.append(sqlite3_column_int64(stmt, 0))
      }
      return out
    }
  }

  /// Oldest timestamp among exhausted rows (diagnostic context). Nil when none.
  func oldestExceedingAttempts(_ max: Int) -> Int64? {
    return queue.sync {
      guard let db = db else { return nil }
      var stmt: OpaquePointer?
      guard sqlite3_prepare_v2(db, "SELECT MIN(t) FROM points WHERE attempts >= ?;", -1, &stmt, nil) == SQLITE_OK else {
        sqlite3_finalize(stmt)
        return nil
      }
      defer { sqlite3_finalize(stmt) }
      sqlite3_bind_int(stmt, 1, Int32(max))
      guard sqlite3_step(stmt) == SQLITE_ROW, sqlite3_column_type(stmt, 0) != SQLITE_NULL else { return nil }
      return sqlite3_column_int64(stmt, 0)
    }
  }

  /// Evict rows that have exhausted their attempts. Returns the number deleted.
  @discardableResult
  func deleteExceedingAttempts(_ max: Int) -> Int {
    return queue.sync {
      guard let db = db else { return 0 }
      var stmt: OpaquePointer?
      guard sqlite3_prepare_v2(db, "DELETE FROM points WHERE attempts >= ?;", -1, &stmt, nil) == SQLITE_OK else {
        sqlite3_finalize(stmt)
        return 0
      }
      defer { sqlite3_finalize(stmt) }
      sqlite3_bind_int(stmt, 1, Int32(max))
      sqlite3_step(stmt)
      // VERIFY: sqlite3_changes reports rows affected by the most recent statement.
      return Int(sqlite3_changes(db))
    }
  }
}
