# Changelog

## v1.4 — current
**Screen reading fixed + the floating ♞ can no longer pile up**

### Why screen reading said "could not read the screen"
The frame listener of the capture was attached to the *same* worker thread that reads the board.
`grabWait()` parks that thread while it waits for a picture, so the listener could never run and
no frame was ever delivered — the panel waited forever and then reported "Could not read the
screen". v1.4 gives the capture its own `chesshint-frames` thread, so pictures arrive while the
board reader and the engine are busy.

* first picture: waits up to 8 s, then keeps watching in the background instead of failing
* a second "start recording" grant now replaces the old capture (two live projections can
  deliver black frames on some phones)
* blank/black frames are detected and explained (some chess apps block capture — protected content)
* if the capture object dies but the token is still valid, **SHOW MY MOVE** rebuilds it instead of
  refusing
* Diagnostics now shows frames received, seconds since the last picture, blank-frame state and
  how many floating windows exist

### Why several floating icons appeared and STOP looked broken
`ensureWindows()` was posted with a 150 ms delay. When the service was destroyed first, that
queued call still ran and re-added the ♞ bubble — a window with **no service behind it**, so the
app believed it was stopped and the STOP button had nothing to stop. Every failed start left
another orphan on screen.

* a delayed/post-destroy `ensureWindows()` is now ignored (`destroyed` flag) and `onDestroy()`
  cancels all pending callbacks
* every window is tracked in a static registry; `purgeAllWindows()` removes any of them, whoever
  created it
* opening the app sweeps strays away automatically when the panel is not running
* the ♞ button now carries a red **✕** — one tap closes everything
* the panel gained **HIDE ♞** (marks stay) and **✖ STOP & CLOSE**
* the home screen gained **Close floating icon** and **Hide ♞ button**
* STOP from the app, the notification and the ♞ all go through the same shutdown path

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
