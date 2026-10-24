# AerialViews + tv-vibehack-firetv Integration Implementation Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make AerialViews the idle screensaver invoked by the tv-vibehack-firetv launcher and replace AerialViews' Projectivy-specific host entry point with an explicit launcher-facing integration.

**Architecture:** Keep AerialViews' playback, media providers, preferences, and screensaver rendering in the existing AerialViews application. Expose a stable explicit intent action for the launcher to start the existing screensaver activity, and have the launcher launch it only after a period of user inactivity and only when AerialViews is installed. Remove the Projectivy wallpaper-provider service/module registration from the AerialViews build after confirming no remaining compile-time consumers; do not reimplement media playback or duplicate media-provider logic inside the launcher.

**Tech Stack:** Android Java (tv-vibehack-firetv launcher), Kotlin/Android (AerialViews), Android manifests, Gradle, GitHub Actions.

## Global Constraints

- Preserve the tv-vibehack-firetv launcher as the Home activity and retain its existing focus, dock, navigation, and HomeKeyService behavior.
- Do not disable Amazon packages, change the TV's live configuration, or merge either pull request as part of repository implementation.
- The launcher must continue to work when AerialViews is not installed; missing screensaver must be a no-op rather than a crash.
- A screensaver launch must be cancelled/rescheduled on user interaction and cancelled when the launcher is not foregrounded.
- AerialViews must remain usable as a standalone screensaver/settings application and keep its existing media providers and playback behavior.
- Keep Fire OS 6 / Android API 25 compatibility in the launcher; avoid APIs newer than the launcher's minSdk 25 unless guarded.
- Use isolated feature branches and reviewable PRs; do not commit generated APKs, build outputs, credentials, or local device identifiers.

---

### Task 1: Define the AerialViews launcher entry point

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/neilturner/aerialviews/ui/screensaver/TestActivity.kt` only if intent action handling is needed
- Test: existing AerialViews unit-test configuration under `app/src/test`

**Interfaces:**
- Consumes: launcher explicit intent action `com.gathin.tvhome.action.START_SCREENSAVER`
- Produces: an exported, resolvable AerialViews screensaver entry point that opens the existing `TestActivity` path

- [ ] **Step 1: Add the explicit launcher action** to the existing screensaver activity intent filter without removing its current exported component behavior.
- [ ] **Step 2: Verify the intent contract** by checking the manifest declares the exact action, default category, and existing activity class.
- [ ] **Step 3: Keep launch behavior compatible** with ordinary direct starts and ensure the action does not change settings-import or app-launch flows.
- [ ] **Step 4: Run AerialViews verification** with `./gradlew :app:testBetaDebugUnitTest :app:spotlessCheck :app:assembleBetaDebug`; expected outcome is a clean unit-test, formatting, and debug-build result.

### Task 2: Launch AerialViews after launcher inactivity

**Files:**
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
- Modify: `launcher/app/src/main/AndroidManifest.xml` only if package visibility or intent declarations prove necessary
- Test: launcher build via `cd launcher && ./gradlew assembleDebug`

**Interfaces:**
- Consumes: `com.gathin.tvhome.action.START_SCREENSAVER` in package `com.neilturner.aerialviews`
- Produces: idle timer that launches AerialViews screensaver after 10 minutes without user interaction

- [ ] **Step 1: Add a main-thread idle timer** using the existing `Handler`; reset it from `onUserInteraction()`, start it when the launcher resumes, and remove callbacks when it pauses/stops.
- [ ] **Step 2: Launch defensively** with an explicit package/action intent; verify the package is installed before launch and catch `ActivityNotFoundException`/security failures so the launcher stays usable.
- [ ] **Step 3: Preserve navigation** by resetting the timer after DPAD, remote, touch, or launcher interactions and by not launching the screensaver while the launcher is backgrounded.
- [ ] **Step 4: Verify the launcher build** with `cd launcher && ./gradlew --no-daemon assembleDebug`; expected outcome is successful APK assembly without changes to existing Home/focus behavior.

### Task 3: Remove Projectivy as the host integration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify or remove: `app/src/main/java/com/neilturner/aerialviews/services/projectivy/WallpaperProviderService.kt`
- Modify/remove only after reference search: Projectivy-only API classes and preferences

**Interfaces:**
- Consumes: explicit launcher intent action from Task 1
- Produces: AerialViews app build with no Projectivy wallpaper-provider service or `:projectivyapi` module dependency

- [ ] **Step 1: Inventory all references** to `projectivyapi`, `WallpaperProviderService`, `tv.projectivy`, and Projectivy-only preference models before deleting any source.
- [ ] **Step 2: Remove the Projectivy service declaration and module dependency** only after all compile-time consumers are accounted for; retain unrelated provider/media code and standalone screensaver functionality.
- [ ] **Step 3: Run unit tests, Spotless, and the beta debug build** with `./gradlew :app:testBetaDebugUnitTest :app:spotlessCheck :app:assembleBetaDebug`; expected outcome is no unresolved Projectivy symbols and no regressions in the app.
- [ ] **Step 4: Review the final source tree** to confirm no active Projectivy manifest entry, module include, or dependency remains.

### Task 4: End-to-end contract and release notes

**Files:**
- Modify: `README.md` in each repository
- Test: both repository build workflows and a Fire OS 6/API 25 runtime smoke test

**Interfaces:**
- Consumes: AerialViews intent action and launcher idle-timer behavior
- Produces: documented install/setup steps and a reproducible smoke-test checklist

- [ ] **Step 1: Document installation and behavior**, including the 10-minute idle timeout, the requirement that AerialViews be installed, and the standalone-launcher fallback when it is absent.
- [ ] **Step 2: Verify the manifest contract and both builds**; on a real Fire TV, confirm screensaver starts after idle, DPAD/Home input returns to the launcher, and no screensaver launch occurs after interaction or while another app is foregrounded.
- [ ] **Step 3: Open draft PRs** in both forks with build results and clearly identify any hardware-only verification that could not be run from CI.

## Unresolved externally observable decisions

- The initial idle timeout is set to 10 minutes as a conservative implementation default; making it user-configurable is intentionally outside this first integration unless existing launcher settings provide a natural supported preference seam.
- Cross-app live-video rendering behind the launcher tiles is not included in this implementation. AerialViews is started as a full-screen idle screensaver, preserving its existing playback pipeline instead of trying to embed a second app's rendering surface into the launcher's view hierarchy.
