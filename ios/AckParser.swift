import Foundation

/// Tolerant parser for the server's upload ACK. Mirrors Android's `AckParser.kt`
/// key-for-key — keep the two in sync.
///
/// WHY THIS EXISTS: the wire contract documents `{"acceptedIds": ["<rowId>", ...]}`,
/// but real deployments wrap it (`{"success":true,"data":{...}}`), rename the field,
/// return objects instead of scalars, or answer 201 with no body at all. The strict
/// parser this replaces returned an empty list for every one of those shapes — and
/// the uploader's 2xx branch treated "zero acks" as complete success, so nothing was
/// ever deleted and the same batch re-uploaded forever with no diagnostic.
///
/// SAFETY: this NEVER invents an id. An unrecognised shape yields an empty list,
/// which the caller now treats as a hard signal (diagnostic + attempt increment),
/// not as success. The caller additionally intersects the result with the ids it
/// actually sent, so a server echoing its OWN primary keys cannot delete an
/// unrelated buffered row by coincidence.
enum AckParser {

  /// Envelope containers searched after the root. First hit wins.
  private static let envelopeKeys = ["data", "result"]

  /// Field names searched inside each container, in order.
  private static let arrayKeys = ["acceptedIds", "acceptedIDs", "accepted", "ids"]

  /// Keys read off an object-shaped array element, in order.
  private static let elementIdKeys = ["id", "rowId", "rowid", "clientId"]

  /// Extract the acknowledged row ids from a response body. Empty for anything
  /// unparseable (an HTML error page from a proxy must degrade, not throw).
  static func parse(_ data: Data?) -> [Int64] {
    guard let data = data, !data.isEmpty,
          let obj = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    else { return [] }

    if let arr = obj as? [Any] { return extract(arr) }
    guard let root = obj as? [String: Any], let arr = findArray(root) else { return [] }
    return extract(arr)
  }

  /// Root fields first, then `data.*`, then `result.*`.
  private static func findArray(_ root: [String: Any]) -> [Any]? {
    for key in arrayKeys {
      if let arr = root[key] as? [Any] { return arr }
    }
    for envelope in envelopeKeys {
      guard let nested = root[envelope] as? [String: Any] else { continue }
      for key in arrayKeys {
        if let arr = nested[key] as? [Any] { return arr }
      }
    }
    return nil
  }

  /// Scalars (String or Number) and objects carrying an id key. Junk is skipped.
  private static func extract(_ arr: [Any]) -> [Int64] {
    var seen = Set<Int64>()
    var out: [Int64] = []
    for value in arr {
      var id: Int64?
      if let dict = value as? [String: Any] {
        for key in elementIdKeys {
          if let coerced = coerce(dict[key]) { id = coerced; break }
        }
      } else {
        id = coerce(value)
      }
      if let id = id, seen.insert(id).inserted { out.append(id) }
    }
    return out
  }

  private static func coerce(_ value: Any?) -> Int64? {
    guard let value = value, !(value is NSNull) else { return nil }
    if let s = value as? String { return Int64(s.trimmingCharacters(in: .whitespaces)) }
    // NSNumber covers Int/Double/Bool bridging out of JSONSerialization.
    if let n = value as? NSNumber { return n.int64Value }
    return nil
  }
}
