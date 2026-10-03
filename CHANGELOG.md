# Changelog

## v1.3 — current
**Screen-recording crash fixed (Android 10–15) + the panel never closes itself**
* The foreground service / MediaProjection order is now **correct for every Android version**:
  * API 26–28 — plain foreground service, then `getMediaProjection()`
  * API 29+ (incl. Android 10, 11, 14, 15) — the media-projection foreground service is started
    **first**, because `getMediaProjection()` throws `SecurityException` otherwise. This was the
    regression introduced in 1.2 and it is what closed the app right after the
    "start recording" dialog on Android 10.
  * If the platform still disagrees, the service **automatically retries the other order**
    instead of dying.
* A capture failure no longer stops the panel: the reason is stored, shown in the app's
  **PROBLEM** card and in Diagnostics, with a **RETRY SCREEN READING** button and a **Share log**
  button (opens the Android share sheet with device + Android version + log).
* New `App` class installs the crash guard before anything else; `Tune` picks the engine hash
  size (8/16/32 MB) and thread count from the device's real heap limit.
* Every version of the APK is published in `apk/`.

## v1.2 — current
**Reliability release**
* Fixed the crash when tapping **SHOW MY MOVE** (memory + guard hardened):
  * the engine hash was cut from 64 MB to 16 MB,
  * the screen reader now works on a downscaled copy of the screenshot instead of a
    full-resolution pixel array (a 1080×2340 screen used to need ~10 MB per frame),
  * every step of the hint path on the UI thread is guarded, and `largeHeap` is enabled.
* **No more surprise calibration overlay**: the panel no longer throws the yellow frame on
  the screen by itself. If a board cannot be found it simply says
  *"No chess board on screen — tap FIT BOARD"*.
* Screen reading on **Android 10 / 11** made much more robust:
  * the service becomes a foreground service in the order each Android version requires
    (projection first on 8–13, foreground first on 14+),
  * the virtual display is created with a retry and reports a real error,
  * if capture fails the panel now **stays alive** and shows a *Retry screen reading* button.
* New **Diagnostics** card: capture status, frame counter, engine state, last error log with
  copy-to-clipboard.
* Crash log (`CrashGuard`) so a failure is a message instead of the app disappearing.

## v1.1
* Fixed the Android 14/15 crash when granting screen recording.
* Overlay no longer shows a top bar / app name.
* New Settings screen: mark style (arrow+rings / arrow / rings / squares), 5 colour sets,
  3 sizes, move text, engine line, labels, frame, status pill, strength, thinking time,
  auto mode, vibration, bubble size — with a live preview.
* New production UI: home screen, floating tile panel, dark theme.

## v1.0
* First release: Stockfish 11 (JNI), screenshot board detection, game tracker, hint arrow.
