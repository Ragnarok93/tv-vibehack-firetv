# Launcher APK upgradeability

## Invariants

- Keep `applicationId` as `com.gathin.tvhome` for upgrades over the existing launcher.
- Keep using the same signing certificate for every installable launcher APK. The local `assembleDebug` workflow and `build-and-install-fire.sh` use the machine's persistent Android debug keystore. The install script now stops if it is missing, rather than silently creating a new signer. Do not delete or regenerate that keystore if you need to upgrade an APK signed by it. For a deliberate first install on a new machine only, set `ALLOW_NEW_DEBUG_KEYSTORE=1`.
- Increase `versionCode` for every distributed release. The current branch starts at version code 5 / version name `2.3-fire`, above the previous version code 4.
- Use `adb install -r` / `pm install -r` for upgrades. Do not uninstall first, because uninstalling can remove app data and permissions.

## CI versus installable builds

GitHub Actions runs `assembleDebug` as a compile check only and does not publish the runner's APK as an upgrade package. GitHub-hosted runners generate ephemeral debug keystores, so their debug APK signatures are not stable across independent runs. Installable builds should be made by the normal local script (same persistent keystore) or by a future release workflow configured with the same signing key.

## Compatibility limit

Android only permits in-place updates when both the package ID and signing identity match, and the incoming version is not a downgrade. A build signed with another key cannot update an existing installation, regardless of its version code. Preserve the signing keystore used for existing installations.
