# Shelter Kotlin Rewrite Block Plans

Status: implementation complete for reachable code; CI and release gates green. Device gates remain required.

## Contract

Files: `app/src/main/aidl/**`, `AndroidManifest.xml`, `Actions.kt`, `ApplicationInfoWrapper.kt`, `UriForwardProxy.kt`, Gradle files.

Dependencies: original Java source.

Checks: AIDL unchanged; Parcelable order unchanged; actions include `START_FILE_SHUTTLE_2` and `PACKAGEINSTALLER_CALLBACK`; manifest preserves authorities, exported flags, permissions, disabled providers; minSdk 24. CI: `assembleDebug`, unit tests, lint, `assembleRelease`.

## Security/Auth

Files: `AuthManager.kt`, `ProfileManager.kt`, `DummyActivity.kt`, `CrossProfileAction.kt`, `FinalizeActivity.kt`, `DeviceAdminReceiver.kt`, `ShelterService.kt`.

Interfaces: steady HMAC verifies version/action/time/nonce/canonical extras; provisioning secret comes only from platform admin extras; same-process grants use random action/extras-bound one-shot tokens; PackageInstaller uses transaction nonce/session binding; FileShuttle uses signed callback token.

Checks: forged TOFU key rejected; forged finalize rejected; resolver accepts only platform forwarders/system aliases; replay, wrong nonce, wrong session, duplicate callback rejected. CI green. Device gates: API 24–29 aliases, API 30+ forwarder flag, admin-extra delivery, process death, callback lifecycle.

## Provisioning

Files: `SetupActivity.kt`, `SetupScreen.kt`, `FinalizeActivity.kt`, `DeviceAdminReceiver.kt`, `CrossProfileAction.kt`, `SettingsStore.kt`.

States: setup requested, waiting for system provisioning, trusted finalization, services available, setup complete, retryable failure.

Checks: exact DPC component and `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`; duplicate finalization idempotent; process recreation/resume; work profile disabled/re-enabled. Device required.

## IPC/Services

Files: `ShelterService.kt`, `FileShuttleService.kt`, `FreezeService.kt`, `KillerService.kt`, `PaymentStubService.kt`, `CrossProfileDocumentsProvider.kt`, `FileProviderProxy.kt`, `InstallationProgressListener.kt`.

Checks: all AIDL methods implemented; callbacks exactly once; binder death and timeout handled; canonical path confinement; FD closure; screen-off freeze delay/UsageStats; PackageInstaller pending-user-action and terminal states.

## Storage/Repository

Files: `SettingsStore.kt`, `AppRepository.kt`, `AppInfo.kt`.

Interfaces: Hilt constructs repository with `SettingsStore`; Activity configures current/sibling `IShelterService` handles at runtime; clone requires sibling binder and never falls back to current profile.

Checks: cancellation suppresses late callback; typed service errors; legacy SharedPreferences migration idempotent; CSV auto-freeze list conversion; local-only versus synchronized settings preserved.

## Compose UI

Files: `MainActivity.kt`, `ui/main/**`, `ui/setup/**`, `ui/settings/**`, `ui/theme/**`.

Checks: Activity owns binders; ViewModels own StateFlow only; main/work tabs configure sibling services; search, refresh, clone, install, uninstall, freeze, shortcuts, widgets/packages, settings permission gates, setup resume.

## Verification/Release

CI workflow `.github/workflows/android.yml` runs:

```text
gradle assembleDebug --no-daemon
gradle testDebugUnitTest --no-daemon
gradle lintDebug --no-daemon
gradle assembleRelease --no-daemon
```

Latest successful run: `32510148097`.

Device-only gates not claimed as complete: managed-profile provisioning/finalization, API 24–29 and OEM resolver aliases, Work Profile enable/disable, File Shuttle/DocumentsUI, PackageInstaller lifecycle, NFC stub, process death, upgrade from original Shelter.
