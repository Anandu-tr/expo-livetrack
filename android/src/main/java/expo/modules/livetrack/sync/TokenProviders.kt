package expo.modules.livetrack.sync

import android.content.SharedPreferences
import expo.modules.livetrack.Prefs

/**
 * Reflectively loads the app-supplied [TokenProvider] named in config. Cached
 * process-wide so it is not re-instantiated per upload. Returns null when
 * unconfigured or the class cannot be loaded (callers fall back to the stored
 * static token).
 */
internal object TokenProviders {
  @Volatile
  private var cached: TokenProvider? = null

  fun load(prefs: SharedPreferences): TokenProvider? {
    val className = prefs.getString(Prefs.KEY_TOKEN_PROVIDER_CLASS, null) ?: return null
    cached?.let { if (it::class.java.name == className) return it }
    return runCatching {
      (Class.forName(className).getDeclaredConstructor().newInstance() as TokenProvider)
    }.getOrNull()?.also { cached = it }
  }
}
