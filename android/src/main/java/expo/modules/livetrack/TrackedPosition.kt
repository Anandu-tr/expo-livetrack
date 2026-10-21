package expo.modules.livetrack

/**
 * The position the tracker last accepted in this process. Event rows recorded
 * outside the service (LOCATION_OFF/ON) take it, because the system's last-known
 * location is null exactly then: the provider is off, or has only just come back.
 */
internal object TrackedPosition {
  @Volatile var latLng: Pair<Double, Double>? = null
}
