# AerialViews + tv-vibehack-firetv Integration Implementation Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make AerialViews the idle screensaver invoked by the tv-vibehack-firetv launcher and replace AerialViews' Projectivy-specific host entry point with an explicit launcher-facing integration.

**Architecture:** Keep AerialViews' media providers and screensaver rendering in the existing AerialViews application. Expose a read-only, caller-checked ContentProvider catalogue of media URIs to tv-vibehack-firetv; the launcher uses Media3 to render supported videos behind its existing UI. The launcher also starts AerialViews' existing full-screen screensaver after inactivity. Remove the Projectivy wallpaper-provider service/module dependency while preserving AerialViews' standalone app and media-selection logic.

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

**Execution status (2026-10-24):** The implementation is committed on feature branches and draft PRs are open: [launcher PR #1](https://github.com/Ragnarok93/tv-vibehack-firetv/pull/1) and [AerialViews PR #1](https://github.com/Ragnarok93/AerialViews/pull/1). Local builds, automated tests, and Fire TV runtime verification remain unverified because the available GitHub Actions workflow is manual-only and no workflow-dispatch action is exposed by the connected GitHub tools.

### Task 1: Expose a secure AerialViews media catalogue

**Files:**
- Create: `app/src/main/java/com/neilturner/aerialviews/services/launcher/LauncherWallpaperProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: existing AerialViews unit-test configuration under `app/src/test`

**Interfaces:**
- Consumes: existing AerialViews `MediaProvider` implementations and their configured media sources
- Produces: `content://com.neilturner.aerialviews.launcher.wallpapers/wallpapers`, with `_id`, `uri`, `type`, and `title` columns; queries are restricted to the `com.gathin.tvhome` caller

- [x] **Step 1: Add a read-only `ContentProvider`** that reuses AerialViews' existing configured providers and returns the four-column media catalogue.
- [x] **Step 2: Restrict callers** to the tv-vibehack-firetv package and return an empty cursor only when no enabled providers have media; provider failures must not crash AerialViews.
- [x] **Step 3: Register the provider** with authority `com.neilturner.aerialviews.launcher.wallpapers` and keep the screensaver activity's explicit launcher action available.
- [ ] **Step 4: Run AerialViews verification** with `./gradlew :app:testBetaDebugUnitTest :app:spotlessCheck :app:assembleBetaDebug`; expected outcome is a clean unit-test, formatting, and debug-build result.

### Task 2: Render AerialViews video backgrounds in the launcher

**Files:**
- Modify: `launcher/app/build.gradle`
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
- Test: launcher build via `cd launcher && ./gradlew --no-daemon assembleDebug`

**Interfaces:**
- Consumes: `content://com.neilturner.aerialviews.launcher.wallpapers/wallpapers` with `uri` and `type` columns
- Produces: a Media3 video layer behind the launcher UI, hidden while no videos are available or the launcher is paused

- [x] **Step 1: Add Media3 dependencies** and a non-focusable `PlayerView` as the first child of the launcher root, behind the existing ambient glow, shelf, and tiles.
- [x] **Step 2: Query the provider off the main thread**, play video rows in shuffled repeat mode, and release the player when the launcher pauses; missing AerialViews, denied access, empty media, and query failures must leave the launcher functional.
- [x] **Step 3: Add a main-thread idle timer** using the existing `Handler`; reset it from `onUserInteraction()`, start it when the launcher resumes, and remove callbacks when it pauses.
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

- [x] **Step 1: Inventory all references** to `projectivyapi`, `WallpaperProviderService`, `tv.projectivy`, and Projectivy-only preference models before deleting any source.
- [x] **Step 2: Remove the Projectivy service declaration and module dependency** only after all compile-time consumers are accounted for; retain unrelated provider/media code and standalone screensaver functionality.
- [ ] **Step 3: Run unit tests, Spotless, and the beta debug build** with `./gradlew :app:testBetaDebugUnitTest :app:spotlessCheck :app:assembleBetaDebug`; expected outcome is no unresolved Projectivy symbols and no regressions in the app.
- [x] **Step 4: Review the final source tree** to confirm no active Projectivy manifest entry, module include, or dependency remains.

### Task 4: End-to-end contract and release notes

**Files:**
- Modify: `README.md` in each repository
- Test: both repository build workflows and a Fire OS 6/API 25 runtime smoke test

**Interfaces:**
- Consumes: AerialViews intent action and launcher idle-timer behavior
- Produces: documented install/setup steps and a reproducible smoke-test checklist

- [x] **Step 1: Document installation and behavior**, including the 10-minute idle timeout, the requirement that AerialViews be installed, and the standalone-launcher fallback when it is absent.
- [ ] **Step 2: Verify the manifest contract and both builds**; on a real Fire TV, confirm screensaver starts after idle, DPAD/Home input returns to the launcher, and no screensaver launch occurs after interaction or while another app is foregrounded.
- [x] **Step 3: Open draft PRs** in both forks with build results and clearly identify any hardware-only verification that could not be run from CI.

### Task 5: Add an on-device launcher settings menu

**Files:**
- Modify: `launcher/app/src/main/res/layout/activity_main.xml`
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java`
- Modify: launcher strings, icon resources, and README
- Add: `.github/workflows/launcher-android-build.yml`

- [x] **Step 1: Add a Launcher button** beside the existing Android Settings button and open a D-pad-friendly popup menu.
- [x] **Step 2: Persist launcher preferences** for AerialViews background/screensaver, idle delay, grid columns, phone-only apps, dock/hidden package lists, and Live TV package.
- [x] **Step 3: Apply preference changes immediately** to playback, the screensaver timer, app filtering, dock ordering, and grid layout.
- [x] **Step 4: Document the menu** and add a pull-request/push Gradle build workflow.
- [ ] **Step 5: Verify the Gradle build and Fire TV runtime behavior**; a successful build and device smoke test remain required before marking this feature fully verified.

## Unresolved externally observable decisions

- The initial idle timeout defaults to 10 minutes and is configurable from the launcher's new Launcher settings menu.
- The first implementation renders videos returned by AerialViews' existing providers; it does not attempt to embed AerialViews' own Activity surface. The separate full-screen idle screensaver uses the existing `TestActivity` entry point.
