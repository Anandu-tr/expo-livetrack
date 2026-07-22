package expo.modules.livetrack.sync

/**
 * Auth-vendor-agnostic seam for minting a fresh upload token. The plugin loads an
 * implementation by fully-qualified class name (see `StartConfig.tokenProviderClass`)
 * via reflection, so it never depends on any specific auth SDK. The implementation
 * lives in the host app.
 *
 * Implementations MUST have a public no-arg constructor and are called on a
 * background thread (never the main thread).
 */
interface TokenProvider {
  /**
   * Return a fresh auth token, or null if unavailable (e.g. signed out).
   *
   * @param forceRefresh when true, bypass any cached token and mint a new one
   *   (used after a 401). When false, a still-valid cached token may be returned.
   */
  fun freshToken(forceRefresh: Boolean): String?
}
