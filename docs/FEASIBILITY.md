# PULSE Phase 1: Technical Feasibility Report

Status: **code written, NOT yet compiled or run on a device.** Everything under "Expected" is reasoning from Android's documented behaviour. Everything under "Observed" is blank until you run the test protocol on the Galaxy A14. Nothing here should be treated as verified until those cells are filled in.

## 1. Key finding: what can and cannot be detected

| Question | Answer | Basis |
|---|---|---|
| Can a normal app learn that the *fingerprint* succeeded on the lock screen? | **No.** | BiometricPrompt only reports results for prompts the app itself starts. There is no public API for the system lock screen's result. |
| Can it learn that the device was unlocked? | **Yes, as "any unlock".** | `ACTION_USER_PRESENT` (dynamic receiver) fires after the keyguard is dismissed. |
| Can it tell fingerprint from PIN / pattern / password / face? | **No** via public APIs. | `USER_PRESENT` carries no method information. |
| Can an app draw over the secure lock screen? | **No** (do not rely on it). | `TYPE_APPLICATION_OVERLAY` windows are not shown over the secure keyguard. |

**Consequence for the product:** PULSE can only honestly offer an **"Any unlock" mode**. It must never be marketed as "fingerprint detection". In practice, on a phone where the user mostly unlocks by side-sensor fingerprint, the animation will look as if it belongs to the sensor, but a PIN unlock will trigger it too.

Possible later refinements (each needs its own feasibility test and a privacy review, and none is in Phase 1):
- Heuristic: a screen-on followed by a very short time to `USER_PRESENT` suggests biometric/no-credential unlock, while a long gap suggests manual PIN entry. This is a guess, so it must be labelled "likely" and never "fingerprint".
- Accessibility service observing window changes. Heavy permission, Play policy scrutiny, and it still does not reveal the method. Not recommended unless Phase 1 shows `USER_PRESENT` is unusable on Samsung.

## 2. What the Phase 1 code does

- `UnlockMonitorService`: user-started foreground service (`specialUse`) that dynamically registers `SCREEN_ON`, `SCREEN_OFF`, `USER_PRESENT`. No polling, no timers, no wake locks. Logs `isInteractive`, `isKeyguardLocked`, `isDeviceLocked`, `isDeviceSecure` with each event.
- `RippleOverlay`: one `TYPE_APPLICATION_OVERLAY` window at a time, not focusable and not touchable, with a watchdog that removes it even if the animator fails. Origin stored as normalized 0..1 and resolved against the overlay's own size each frame.
- `EventLog`: local file log of real events with timestamps and latency from trigger to `addView` and to first drawn frame.
- `MainActivity`: permission status, start/stop, origin sliders, preview, copy/clear log.
- Debounce of 1500 ms against duplicate triggers.

## 3. Risks to verify on the Galaxy A14 (Android 15)

1. **Does `USER_PRESENT` arrive reliably** with the service running and the screen off for a long time (Samsung's battery management may kill the process)?
2. **Latency:** how long after the fingerprint success does the first frame appear? Samsung's own unlock transition runs first, so the ripple may start slightly late. Logged as "+Nms after trigger".
3. **Overlay timing:** is `addView` accepted immediately, or delayed/blocked on One UI?
4. **Foreground service rules on Android 14/15:** the `specialUse` type requires the manifest property declared here. Starting from the activity (user-initiated) should be allowed; auto-start after boot is restricted and is **not** attempted in Phase 1.
5. **Process survival:** does One UI stop the service (Device care / "sleeping apps")? Test with and without battery optimisation exemption.
6. **Face / swipe-only unlock** produce the same event; confirm and document.

## 4. Test protocol (fill in the Observed column)

Build in Android Studio (Ladybug or newer), install on the A14, grant overlay permission, tap "Start monitor", set origin with the sliders and press "Preview ripple now" to check placement against the real sensor position.

| # | Scenario | Expected | Observed |
|---|---|---|---|
| 1 | Screen off, then fingerprint unlock | SCREEN_ON, USER_PRESENT, ripple | |
| 2 | Screen off, then PIN unlock | Same events as #1 (method not distinguishable) | |
| 3 | Screen off, then pattern unlock | Same as #2 | |
| 4 | Power button, then screen on and wait on lock screen (no unlock) | SCREEN_ON only, **no** ripple | |
| 5 | Screen off before the ripple finishes | Overlay removed by SCREEN_OFF handler | |
| 6 | Unlock twice within 1.5 s | Second trigger ignored (debounce) | |
| 7 | Revoke overlay permission, then unlock | Log "BLOCKED", no crash | |
| 8 | Leave phone locked for 30+ min, then unlock | Event still delivered? | |
| 9 | Touch the screen during the ripple | Touches pass through to the app below | |
| 10 | Battery optimisation on vs. exempt | Compare #8 | |
| 11 | Rotate to landscape and preview | Origin follows normalized position | |

For each unlock row, copy the log from the app ("Copy log") and note the `+Nms` latency values.

## 5. Go / no-go criteria for Phase 2

Proceed if: `USER_PRESENT` is delivered reliably (rows 1 to 3, 8), the first frame appears within an acceptable delay of the unlock (suggested under about 300 ms after the event), and no stuck overlay occurs (rows 5, 7, 9). If delivery is unreliable on One UI, the fallback options are a visible "enable to keep running" flow plus battery-settings guidance; do not promise uninterrupted operation.

## 6. Not done / not claimed

- No APK was compiled or tested; the build environment had no Android SDK.
- No frame-rate, CPU, GPU or battery measurements exist yet (Phase 6).
- Default origin (0.95, 0.40) is a placeholder, not a measured sensor position.
