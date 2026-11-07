# :kiosk

COSU (Corporate-Owned Single-Use) lock-task / kiosk control, built clean-room on the
native `DevicePolicyManager` lock-task APIs (Headwind's real COSU engine is closed
"Pro"; OSS only ships an overlay hack).

This module does **not** depend on `:policy` — its result type (`KioskResult`) is
defined locally.

## Contents

| Type | Role |
|------|------|
| `KioskController` | interface; never throws — returns `KioskResult` |
| `KioskResult` | `Ok` / `Failed(reason)` / `Unsupported` |
| `LockTaskKioskController` | real impl over `DevicePolicyManager` lock-task APIs |
| `StubKioskController` | no-op fallback for non-Device-Owner / tests (all ops `Unsupported`) |
| `KioskToggles` + `lockTaskFeatures()` | kiosk UI toggles → `LOCK_TASK_FEATURE_*` bitmask (`KioskFeatures.kt`) |
| `CrashLoopGuard` | crash-loop protection (ported from Headwind `CrashLoopProtection`) |
| `FaultStore` | counter persistence behind an interface |
| `SharedPrefsFaultStore` | production store (SharedPreferences, synchronous `commit()`) |
| `InMemoryFaultStore` | Android-free store for unit tests |
| `KioskReturnPolicy` | single-app kiosk: reopen the pinned app, or hold on the "App closed" screen once the guard trips (never ends kiosk) |
| `ExitPinGate` | the on-device exit: none without a PIN; wrong PINs lock it for longer each time, timed on uptime |
| `PinAttemptStore` | attempt-count persistence (`SharedPrefsPinAttemptStore`, `InMemoryPinAttemptStore`) |

## What `LockTaskKioskController` does

Constructed directly with `(dpm: DevicePolicyManager, admin: ComponentName)` — no
`DpmHandle`. Guards `isDeviceOwnerApp` + `SDK_INT` on every call.

- **`enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int)`** —
  `setLockTaskPackages` (allowlist + the agent's own package), `setLockTaskFeatures(features)`
  on API 28+ (`features` is a `LOCK_TASK_FEATURE_*` bitmask, usually from
  `lockTaskFeatures(KioskToggles)`), and `addPersistentPreferredActivity` for
  `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT` so `homeComponent` owns HOME: the
  agent's kiosk launcher, or a single-app kiosk's own HOME app (below). The HOME claim made
  before is released first, whichever package it named (the agent's or an allowlisted app's).
- **`exit()`** — releases the HOME claim the same way, clears the allowlist, and sets
  lock-task features to `LOCK_TASK_FEATURE_NONE` (API 28+).
- **`isLocked(context)`** — `ActivityManager.lockTaskModeState == LOCK_TASK_MODE_LOCKED`.
- **`allowedPackages()`** — `getLockTaskPackages(admin)`.

Lock-task base APIs need API 21; `setLockTaskFeatures` needs API 28. The module's
`minSdk` is 24, so the base APIs are always available and only features are guarded.
Real lock-task requires Device Owner (provisioned via `:app`).

## How `:app` wires it

`:kiosk` configures device-level policy; the kiosk activity in `:app` completes the
loop. `:app` is responsible for (done there, not here):

1. **Manifest** — the kiosk launcher is `com.mdmesh.agent.KioskLauncherActivity`, declared
   `android:lockTaskMode="if_whitelisted"`. The HOME intent filter sits on a disabled
   `activity-alias`, `.KioskHomeAlias`, that targets it; `kiosk.enter` enables the alias and
   passes it as `homeComponent`, `kiosk.exit` disables it so HOME falls back to the OEM launcher:

   ```xml
   <activity
       android:name=".KioskLauncherActivity"
       android:exported="true"
       android:launchMode="singleInstance"
       android:excludeFromRecents="true"
       android:stateNotNeeded="true"
       android:lockTaskMode="if_whitelisted" />

   <activity-alias
       android:name=".KioskHomeAlias"
       android:targetActivity=".KioskLauncherActivity"
       android:exported="true"
       android:enabled="false">
       <intent-filter>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.HOME" />
           <category android:name="android.intent.category.DEFAULT" />
       </intent-filter>
   </activity-alias>
   ```

2. **Start/stop lock task** — once `LockTaskKioskController.enter(...)` has
   allowlisted the package, `KioskLauncherActivity` calls `startLockTask()` in the
   foreground (and `stopLockTask()` on exit).

3. **`DISALLOW_CREATE_WINDOWS`** — set by `AdminReceiver` in `onLockTaskModeEntering(...)`
   and cleared in `onLockTaskModeExiting(...)` to block apps from drawing over the kiosk.

4. **Crash-loop wiring** — `KioskLauncherActivity` gets a `CrashLoopGuard` from Hilt and
   calls `registerFault()` each time the pinned single app returns to HOME; once
   `isCrashLoopDetected()` trips, it stops reopening the app and holds in kiosk on an "App closed" screen
   (`KioskReturnPolicy`), so a crashing app can't loop. Kiosk stays on: only the admin-PIN exit or the server ends it.

5. **A single-app kiosk whose app is a HOME app** — when the pinned app declares a HOME activity
   (`ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT`, API 28+), `KioskApplier` makes that
   activity HOME instead of the agent's launcher, and starts it through the HOME intent with
   `ActivityOptions.setLockTaskEnabled(true)`. Android then runs it as the home screen: Back has
   nothing to leave to, and Android 16 does not draw its desktop-windowing app handle (the "…"
   bar) over it, as it does over any other app's window. The launcher still shows its splash
   until the app is installed, then hands HOME over (`KioskApplier.handHomeToPinnedApp`).

   Android restarts a HOME app that crashes in place, in its own task ("Not force finishing home
   activity"), so the launcher is not underneath to count the returns and lock task never ends.
   Two things keep the guard working:

   - every kiosk holds Android's crash dialogs back (`setErrorDialogsQuiet`,
     `DISALLOW_SYSTEM_ERROR_DIALOGS`, lifted on exit), so a crashed app is closed and restarted
     instead of waiting behind "has stopped" for a tap nobody makes;
   - the app reports each start through the local API (`kiosk.started`), and
     `PinnedHomeRestarts` counts it on the same `CrashLoopGuard`. Once it trips,
     `KioskApplier.takeHomeBack` gives HOME back to the launcher, which holds on its "App
     closed" screen; "Open again" hands HOME to the app once more. An app that does not report
     its starts has no crash-loop guard in this mode.

   With the app as HOME the launcher's screen is not shown, so neither is its on-device exit
   (the 7-tap corner or the exit button): such a kiosk is ended from the server.

## Crash-loop algorithm

Ported from `reference/.../util/CrashLoopProtection.java`. More than
`LOOP_CRASHES` (3) faults within `LOOP_TIME_SPAN` (60_000 ms) trips the guard — i.e.
the 4th crash inside the window. A fault after the window restarts the count; an
aged-out window resets on the next `isCrashLoopDetected()` check. The clock is
injectable (`now: () -> Long`) and the counter sits behind `FaultStore`, so the
logic is unit-tested on the JVM (`src/test/.../CrashLoopGuardTest.kt`).
