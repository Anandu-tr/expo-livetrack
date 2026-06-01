# Foreground Service justification — Google Play Console

Android 14+ requires every declared foreground-service type to be justified in the
Play Console (**App content → Foreground service permissions**). `expo-livetrack`
declares a single foreground service of type **`location`**.

- **Permission:** `FOREGROUND_SERVICE_LOCATION`
- **Service:** `expo.modules.livetrack.TrackingService`
  (`android:foregroundServiceType="location"`, `android:exported="false"`)
- **Foreground-service type:** `location`

---

## Justification text (paste into the Play Console form)

> This app is a field-staff tool that records the user’s route and distance
> travelled during their work day. We use a `location`-type foreground service to
> continuously collect GPS location while tracking is active — including when the
> app is in the background or the screen is off — because field staff keep the
> phone locked and pocketed between visits.
>
> A foreground service is required because the location stream must not be
> interrupted: dropped points create gaps that corrupt the route and the distance
> totals our customers rely on for visit verification and travel reporting.
> Periodic/deferred background work (WorkManager, JobScheduler) cannot deliver the
> continuous, real-time location updates this use case needs, and Android stops
> background location for apps that are not running a foreground service.
>
> The service runs **only after** the user has seen an in-app prominent disclosure
> (location collection, that it happens in the background even when the app is
> closed, and the purpose) and has affirmatively consented, and **only while**
> tracking is active. A persistent notification informs the user that tracking is
> running, and the user can stop tracking at any time from within the app.

---

## Notes for the reviewer / submitter

- The service is started by the app (after consent) and by the boot /
  package-replaced receiver **only to re-arm tracking that was already active**.
- The persistent foreground notification is the user-visible signal mandated for
  `location` foreground services.
- Demo evidence for the background-location declaration: see
  [`demo-video-script.md`](./demo-video-script.md).
