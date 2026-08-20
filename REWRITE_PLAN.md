# Shelter Kotlin Rewrite — Corrected Implementation Plan

**Status: NOT READY FOR IMPLEMENTATION.**

This plan incorporates reviewer findings verified against `/data/data/com.termux/files/home/tmp/shelter-original`. No production rewrite starts until Contract Freeze passes.

## 0. Verified corrections

### 0.1 `UriForwardProxy` reviewer finding corrected

Reviewer claimed original parcel format writes a file descriptor and opens it during deserialization. Source proves otherwise.

Original wire format:

```java
writeToParcel(Parcel dest, int flags) {
    dest.writeBinderArray(new IBinder[]{mOpener.asBinder()});
}

createFromParcel(Parcel source) {
    IBinder[] arr = new IBinder[1];
    source.readBinderArray(arr);
    IUriOpener opener = IUriOpener.Stub.asInterface(arr[0]);
    return new UriForwardProxy(opener);
}
```

Kotlin MUST preserve this exact binder-array format. No `readFileDescriptor()`, no `writeFileDescriptor()`, no `@Parcelize`, no deserialization-time URI open. The URI resolver remains in the originating profile through `IUriOpener.openFile(mode)`.

### 0.2 Keystore cannot directly share keys across profiles

Android Keystore namespaces differ between parent and managed profiles. A key generated independently in each profile cannot verify the other profile.

The design therefore uses two phases:

1. **Bootstrap:** preserve original first-key exchange semantics, but only during an explicit provisioning/bootstrap state and only from the exact Shelter component resolved across profiles.
2. **Steady state:** store the shared raw HMAC key in an app-private encrypted store, with an Android Keystore key protecting local storage. Both profiles store the same shared key after bootstrap.

Do not claim that Keystore alone provides cross-profile authentication. Do not silently replace original TOFU with independent Keystore keys.

### 0.3 Exact action inventory

These strings are protocol constants and must not be renamed:

- `FINALIZE_PROVISION`
- `START_SERVICE`
- `TRY_START_SERVICE`
- `INSTALL_PACKAGE`
- `UNINSTALL_PACKAGE`
- `UNFREEZE_AND_LAUNCH`
- `PUBLIC_UNFREEZE_AND_LAUNCH`
- `PUBLIC_FREEZE_ALL`
- `FREEZE_ALL_IN_LIST`
- `START_FILE_SHUTTLE`
- `START_FILE_SHUTTLE_2`
- `SYNCHRONIZE_PREFERENCE`
- `PACKAGEINSTALLER_CALLBACK`
- `ACTION_RESUME_SETUP = net.typeblog.shelter.RESUME_SETUP`
- `ACTION_PROFILE_PROVISIONED = net.typeblog.shelter.PROFILE_PROVISIONED`

`START_FILE_SHUTTLE_2` prevents a chooser when the same operation crosses in reverse direction. `PACKAGEINSTALLER_CALLBACK` is delivered to `DummyActivity.onNewIntent()` by PackageInstaller and is not optional.

### 0.4 Exact provisioning action

Original manifest uses `android.app.action.PROVISIONING_SUCCESSFUL` for `FinalizeActivity`, not `MANAGED_PROFILE_PROVISIONED`. Preserve this action and `BIND_DEVICE_ADMIN` permission. The device-admin receiver retains its original provisioning actions.

### 0.5 Compatibility baseline

Keep `minSdk 24` for first rewrite. Raising it to 26 is a product decision, not an architecture cleanup. Pre-O branches are still part of current behavior and must remain until device/API coverage proves removal is acceptable.

---

## 1. Scope and invariants

### Goal

Rewrite Java implementation to Kotlin while preserving Shelter's observable behavior:

- Android Work Profile provisioning and finalization
- parent/managed cross-profile intent routing
- app listing, labels, icons, hidden/frozen state
- clone, install, uninstall, APK install
- unfreeze-and-launch, linked packages, auto-freeze
- cross-profile widgets and package allowlisting
- File Shuttle and DocumentsUI integration
- optional payment stub
- settings and cross-profile settings synchronization
- launcher shortcuts
- service cleanup and restart behavior

### Non-goals

- No adb/root privileges
- No custom sandbox replacing Android Work Profile
- No AIDL protocol redesign in this rewrite
- No silent feature removal
- No new network, analytics, telemetry, or backend
- No speculative abstraction for one implementation

### Hard invariants

1. Package remains `net.typeblog.shelter`.
2. AIDL files remain byte-for-byte unchanged.
3. Parcelable field order and transport shape remain unchanged.
4. Action strings, direction flags, manifest authorities, exported state, permissions, and component defaults remain compatible.
5. Only the managed-profile DPC performs DPM profile-owner operations.
6. Every remote callback has bounded lifetime and exactly-once completion.
7. Every forwarded FD has an explicit owner and terminal cleanup path.
8. Provisioning can resume after process death, activity removal, notification delay, or profile disablement.

---

## 2. Contract Freeze phase — mandatory before implementation

Create a machine-readable contract inventory and tests before porting business logic.

### 2.1 AIDL contract inventory

Copy these unchanged from original:

```text
app/src/main/aidl/net/typeblog/shelter/services/
  IShelterService.aidl
  IFileShuttleService.aidl
  IAppInstallCallback.aidl
  IGetAppsCallback.aidl
  ILoadIconCallback.aidl
  IStartActivityProxy.aidl
  IFileShuttleServiceCallback.aidl
app/src/main/aidl/net/typeblog/shelter/util/
  ApplicationInfoWrapper.aidl
  UriForwardProxy.aidl
  IUriOpener.aidl
```

Exact `IShelterService` methods:

- `ping()`
- `stopShelterService(boolean kill)`
- `getApps(IGetAppsCallback callback, boolean showAll)`
- `loadIcon(ApplicationInfoWrapper info, ILoadIconCallback callback)`
- `installApp(ApplicationInfoWrapper app, IAppInstallCallback callback)`
- `installApk(UriForwardProxy uri, IAppInstallCallback callback)`
- `uninstallApp(ApplicationInfoWrapper app, IAppInstallCallback callback)`
- `freezeApp(ApplicationInfoWrapper app)`
- `unfreezeApp(ApplicationInfoWrapper app)`
- `hasUsageStatsPermission()`
- `hasSystemAlertPermission()`
- `hasAllFileAccessPermission()`
- `getCrossProfileWidgetProviders()`
- `setCrossProfileWidgetProviderEnabled(String pkgName, boolean enabled)`
- `setStartActivityProxy(IStartActivityProxy proxy)`
- `getCrossProfilePackages()`
- `setCrossProfilePackages(List<String> packages)`

Exact `IFileShuttleService` methods:

- `ping()`
- `loadFiles(String path)`
- `loadFileMeta(String path)`
- `openFile(String path, String mode)`
- `openThumbnail(String path, Point sizeHint)`
- `createFile(String path, String mimeType, String displayName)`
- `deleteFile(String path)`
- `isChildOf(String parentPath, String childPath)`

### 2.2 Parcelable compatibility contracts

#### `ApplicationInfoWrapper`

Manual Kotlin `Parcelable`. Preserve exact order:

1. `ApplicationInfo` parcelable
2. nullable `String mLabel`
3. `byte mIsHidden` (`0` or `1`)

Preserve:

- nullable label after construction
- `getPackageName`, `getLabel`, `getSourceDir`, `getSplitApks`, `getEnabled`, `isHidden`, `getInfo`, `isSystem`
- `describeContents() == mInfo.packageName.hashCode()`
- `CREATOR.newArray(size)` returns array of requested size

Add local round-trip tests for null label, split APKs, hidden state, and descriptor value.

#### `UriForwardProxy`

Manual Kotlin `Parcelable`. Preserve exact order and shape:

1. write one `IBinder[]` using `Parcel.writeBinderArray`
2. read one `IBinder[]` using `Parcel.readBinderArray`
3. convert binder with `IUriOpener.Stub.asInterface`

`UriForwardProxy(Context, Uri)` creates an `IUriOpener.Stub` whose `openFile(mode)` calls the originating `ContentResolver`. `open(mode)` catches `RemoteException` and returns null. `newArray(size)` returns requested size.

Add a binder round-trip test with fake `IUriOpener`; add integration test that returned `ParcelFileDescriptor` closes on all terminal paths.

### 2.3 Manifest inventory

Manifest must preserve original behavior:

- Main activity `singleTask`, exported launcher
- Setup activity `singleTask`, not exported
- Dummy activity `singleTask`, exported, excluded from recents
- Dummy intent filters containing all 13 Dummy actions, including `_2` and PackageInstaller callback
- Finalize activity exported with `BIND_DEVICE_ADMIN`, `PROVISIONING_SUCCESSFUL` filter
- Device admin receiver actions: `DEVICE_ADMIN_ENABLED`, `DEVICE_ADMIN_DISABLED`, `PROVISION_MANAGED_PROFILE`
- File provider authority `net.typeblog.shelter.files`, exported false, grant URI permissions
- Documents provider authority `net.typeblog.shelter.documents`, exported true, `MANAGE_DOCUMENTS`, initially disabled
- Payment service exported true, initially disabled, `BIND_NFC_SERVICE`
- `ShelterService` and `FileShuttleService` exported with `BIND_DEVICE_ADMIN`
- `foregroundServiceType="systemExempted"` where present in original
- exact permissions and feature declarations

Do not enable DocumentsProvider or PaymentStub by default. Settings explicitly enable/disable their components.

### 2.4 Cross-profile direction matrix

`ProfileManager.enforceWorkProfilePolicies()` must register exact directions:

| Intent/filter | Direction |
|---|---|
| START_SERVICE | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| TRY_START_SERVICE | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| UNFREEZE_AND_LAUNCH | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| FREEZE_ALL_IN_LIST | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| START_FILE_SHUTTLE | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| SYNCHRONIZE_PREFERENCE | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| INSTALL_PACKAGE | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| UNINSTALL_PACKAGE | `FLAG_MANAGED_CAN_ACCESS_PARENT` |
| PUBLIC_FREEZE_ALL | `FLAG_PARENT_CAN_ACCESS_MANAGED` |
| FINALIZE_PROVISION | `FLAG_PARENT_CAN_ACCESS_MANAGED` |
| START_FILE_SHUTTLE_2 | `FLAG_PARENT_CAN_ACCESS_MANAGED` |
| SEND / SEND_MULTIPLE `*/*` | `FLAG_PARENT_CAN_ACCESS_MANAGED` |
| browsable http/https | `FLAG_PARENT_CAN_ACCESS_MANAGED` |

Policy tests must assert action-to-flag mapping before device tests.

---

## 3. Authentication design

### 3.1 Threat model

Cross-profile exported activities can be reached through system intent forwarding. Signature authentication protects high-privilege actions. Public actions remain intentionally unsigned but must validate inputs and profile direction.

### 3.2 Bootstrap protocol

Preserve compatibility with original first-use behavior, but narrow acceptance:

1. Setup begins: clear stale shared auth state in both profiles where possible.
2. Parent profile initiates first authenticated service request.
3. Sender generates a cryptographically random 256-bit shared secret.
4. First request carries `protocol_version`, `auth_key`, `bootstrap_nonce`, action, and provisioning/session marker.
5. Managed-side receiver accepts `auth_key` only when all are true:
   - caller action is an allowed bootstrap action;
   - receiver is the expected Shelter component in managed profile;
   - profile is in provisioning/bootstrap state;
   - nonce is unused and timestamp is within the bootstrap window;
   - package/component resolution points to the other Shelter profile.
6. Receiver stores shared secret in encrypted app-private storage protected by a local Android Keystore key.
7. Subsequent requests carry timestamp, nonce, action, protocol version, and canonicalized relevant extras in HMAC-SHA256 payload.
8. Receiver rejects expired, duplicate nonce, wrong-action, malformed, wrong-profile, and wrong-key requests.
9. Bootstrap state closes after service binding and policy finalization.

No design can obtain mutual cryptographic authentication before first shared secret exchange without a preinstalled common trust anchor. Document this limitation. Do not market bootstrap as cryptographically authenticated before trust establishment.

### 3.3 Payload canonicalization

Sign all security-relevant values, not only action/timestamp. Canonical payload includes:

- protocol version
- action
- timestamp
- nonce
- package name where present
- linked package list in stable order
- install/uninstall package
- preference name and typed value
- list/freeze package values

Reject unknown protocol versions and duplicate extras that create ambiguous interpretation.

### 3.4 Same-process bypass

Preserve original 5-second one-shot bypass only for:

- `INSTALL_PACKAGE`
- `UNINSTALL_PACKAGE`
- `UNFREEZE_AND_LAUNCH`

Require an in-process registration token, exact action, one-shot consumption, and expiry. Never accept the bypass from a remote profile. Keep `sHasRequestedPermission` equivalent state lifecycle-safe.

### 3.5 Tests

- first bootstrap succeeds only in permitted state;
- arbitrary exported intent cannot bootstrap;
- valid signature succeeds;
- wrong key/action/extras fails;
- replayed nonce fails;
- expired timestamp fails;
- same-process token is one-shot and expires;
- unsigned actions reject malformed package lists and wrong profile direction;
- reset invalidates old key.

---

## 4. Provisioning state machine

Use persistent state, not only Activity result codes.

### 4.1 States

```text
NOT_STARTED
PROVISIONING_REQUESTED
SYSTEM_PROVISIONED_WAITING_FINALIZE
FINALIZATION_RECEIVED_IN_MANAGED
POLICIES_APPLIED
SERVICES_AVAILABLE
SETUP_COMPLETE
FAILED_RETRYABLE
FAILED_TERMINAL
```

Persist state using DataStore, with exact migration from original keys:

- `PREF_IS_SETTING_UP`
- `PREF_HAS_SETUP`
- `PREF_AUTH_KEY`
- auto-freeze lists and all settings keys

### 4.2 State transitions

1. Main opens setup: `NOT_STARTED → PROVISIONING_REQUESTED`.
2. Before launching system provisioning, verify `isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE)`.
3. Reset stale auth bootstrap state before a retry.
4. Launch `ACTION_PROVISION_MANAGED_PROFILE` with exact DPC component and skip-encryption extra.
5. System invokes `FinalizeActivity` with `android.app.action.PROVISIONING_SUCCESSFUL` on O+.
6. `FinalizeActivity` forwards `FINALIZE_PROVISION` to managed `DummyActivity`.
7. Managed DummyActivity applies policies, restrictions, settings, and marks finalization.
8. Managed side signals parent setup activity with `ACTION_PROFILE_PROVISIONED`.
9. Parent checks `isWorkProfileAvailable()` and marks `SETUP_COMPLETE` only after service probe succeeds.
10. If system provisioning succeeds but managed Shelter was not launched, `ACTION_RESUME_SETUP` opens an action-required page and waits for user/system completion.
11. If Work Profile is disabled, show actionable enable-profile state; do not mark setup failed.
12. On process death, reconstruct UI from persistent state and probe profile availability.

### 4.3 Compose boundary

Compose renders state. `SetupActivity` owns ActivityResult launchers and receives `onNewIntent`. ViewModel owns persisted state transitions. Do not infer completion from a single `ActivityResult`.

Required runtime scenarios:

- user cancels provisioning;
- provisioning activity missing;
- provisioning succeeds but notification is not tapped;
- process killed during provisioning;
- setup activity removed from recents;
- profile disabled then re-enabled;
- finalization intent delivered more than once;
- profile already exists on app restart.

---

## 5. Cross-profile action and DummyActivity design

### 5.1 Thin shell, real handlers

`DummyActivity` remains `android.app.Activity`, `singleTask`, no Compose UI. It owns only:

- profile-owner policy initialization;
- Android 13 notification permission continuation;
- authentication gate;
- dispatch;
- PackageInstaller `onNewIntent` callback;
- pre-Q `onActivityResult` callback;
- permission continuation.

Handlers are focused classes, not sealed objects carrying hidden global state:

```text
CrossProfileDispatcher
  StartServiceHandler
  TryStartServiceHandler
  PackageInstallHandler
  PackageUninstallHandler
  FinalizeProvisionHandler
  UnfreezeLaunchHandler
  PublicFreezeAllHandler
  FreezeAllInListHandler
  FileShuttleHandler
  PreferenceSyncHandler
```

Handlers receive a narrow `DummyActivityContext`/dependencies object. They do not call UI or DataStore singletons directly.

### 5.2 Action behavior parity

- `START_SERVICE`: bind service foreground as original, return binder in result bundle.
- `TRY_START_SERVICE`: return `RESULT_OK`; preserve disabled-profile cancellation behavior.
- `INSTALL_PACKAGE`: pre-Q system intent; Q+ PackageInstaller session; support base APK, split APKs, direct `UriForwardProxy` URI, progress, pending user action, failure, and cleanup.
- `UNINSTALL_PACKAGE`: pre-Q system intent; Q+ PackageInstaller uninstall and callback.
- `FINALIZE_PROVISION`: managed policy finalization; parent setup notification; duplicate-safe.
- `UNFREEZE_AND_LAUNCH`: parent forwarding, linked package arrays, per-package should-freeze flags, launch intent null handling.
- `PUBLIC_FREEZE_ALL`: parent loads auto-freeze list and forwards to managed profile.
- `FREEZE_ALL_IN_LIST`: managed hides listed apps, stops FreezeService, clears pending work.
- `START_FILE_SHUTTLE` / `_2`: permission checks, service bind, callback, timeout, finish.
- `SYNCHRONIZE_PREFERENCE`: typed bool/int compatibility, allowlisted key names only, apply settings and policies.

Reject unknown actions by finishing without side effects.

---

## 6. PackageInstaller transaction design

Create `PackageInstallTransactionManager` used only by DummyActivity handlers.

### 6.1 Transaction record

Each transaction contains:

- local transaction ID;
- PackageInstaller session ID;
- operation install/uninstall;
- callback binder;
- forwarded URI/proxy owner;
- creation timestamp;
- terminal flag guarded atomically;
- cleanup status.

Persist minimal session ID/operation state if process death can occur before callback. On startup, reconcile active PackageInstaller sessions and deliver terminal failure for orphaned callbacks.

### 6.2 Exactly-once completion

Terminal states:

- success;
- user action required then success/cancel;
- explicit failure;
- cancellation;
- binder death;
- timeout/process recovery.

Every terminal state:

1. atomically claims transaction;
2. calls remote callback at most once;
3. clears FileProvider proxy/FD ownership;
4. unregisters PackageInstaller callback;
5. closes session resources;
6. dismisses progress UI on main thread;
7. finishes DummyActivity.

`PACKAGEINSTALLER_CALLBACK` must be handled in `onNewIntent()` and must validate transaction/session identity before acting.

### 6.3 Install stream safety

Use `Dispatchers.IO` for piping. Never use `available()` as APK length. Copy until EOF, call `fsync`, close streams/session on all exceptions. Preserve split APK order. Return explicit error code instead of swallowing `IOException`.

---

## 7. Services and lifecycle

### 7.1 ShelterService

Implement every AIDL method. Keep profile-owner checks and API guards. Binder callbacks originate on binder threads; service work runs on a service-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and callbacks are protected from `RemoteException`.

Preserve:

- app filtering and hidden-last alphabetical sort;
- system app enable/unhide behavior;
- non-system install/uninstall delegation through start-activity proxy;
- `installApk` UriForwardProxy flow;
- cross-profile widget/package methods;
- foreground bind extra;
- `stopShelterService(kill)` semantics, including pending FreezeService guard.

Cancel service scope in `onDestroy`. Never let a remote callback keep Activity/context alive indefinitely.

### 7.2 FreezeService

Keep process-level synchronized pending package list. Use AlarmManager for screen-off delay and registered receivers for screen on/off. Preserve UsageStats snapshot timing and foreground skip condition. Create notification channels before `startForeground`. Verify targetSdk 35 foreground-service restrictions on device.

### 7.3 FileShuttleService

Keep 10-second idle self-destruct, but make timer reset behavior explicit for every binder operation. Validate all paths after resolving `DUMMY_ROOT`; reject traversal outside external storage. Bound thumbnail work and close pipe ends. Return explicit errors for missing files, invalid modes, and unsupported operations.

### 7.4 KillerService

Do not use `START_STICKY` if it can resurrect with stale binder references. Use non-sticky semantics matching original lifecycle. Handle binder death and null references. Kill services once, not repeatedly from `onDestroy`, `onTaskRemoved`, and trim callbacks.

### 7.5 PaymentStubService

Keep disabled by default. Enable only through settings. Verify NFC service binding and profile behavior on supported device; report unsupported explicitly.

---

## 8. DocumentsProvider and FD safety

`CrossProfileDocumentsProvider` is synchronous because DocumentsProvider API is synchronous. Coroutines may run behind the service, but provider calls require bounded blocking.

### 8.1 Service acquisition

- one lock protecting service reference and in-flight bind;
- one active bind request at a time;
- monotonic deadline, not unbounded `wait()`;
- timeout returns `FileNotFoundException`/`RemoteException` as appropriate;
- `IBinder.DeathRecipient` clears dead reference and wakes waiters;
- callback ignores late results after timeout;
- release service reference after idle timeout and provider shutdown.

### 8.2 Path/document safety

- preserve `DUMMY_ROOT = /shelter_storage_root/`;
- canonicalize resolved paths;
- reject parent traversal and paths outside allowed storage root;
- validate mode (`r`, `w`, `rw`) before forwarding;
- preserve DocumentsContract columns and MIME metadata;
- close/propagate ParcelFileDescriptor correctly;
- use `CancellationSignal` where provider API supplies one.

### 8.3 Provider tests

- query root/document/children;
- open read/write;
- thumbnail pipe completion;
- create/delete;
- child relationship boundaries;
- missing service timeout;
- binder death during call;
- concurrent callers;
- path traversal rejection;
- late callback after timeout;
- FD closure under success and failure.

---

## 9. Data layer and migration

### 9.1 Models

Keep `ApplicationInfoWrapper` as transport model. Convert to separate immutable `AppInfo` domain model. Do not put `Drawable` in long-lived domain state; use icon bitmap/cache keyed by package and profile.

### 9.2 Repository

`AppRepository` wraps AIDL callbacks with cancellable suspend functions. On cancellation, do not pretend remote work was cancelled; ignore late callback through request token. Map `RemoteException`, `DeadObjectException`, and service-null to typed errors.

Repository must cover:

- app list;
- icon load;
- install/uninstall;
- APK install;
- freeze/unfreeze;
- permissions;
- widget providers;
- cross-profile packages.

### 9.3 DataStore migration

Use `SharedPreferencesMigration` with an explicit key map. Preserve original preference names and values before transforming:

- booleans remain booleans;
- integer freeze delay remains seconds;
- comma-separated auto-freeze lists split with empty entries removed;
- linked package values remain comma-separated only in shortcut intents;
- auth key imported during upgrade, then encrypted at rest;
- unknown keys retained or logged, never silently interpreted as a different setting.

Migration is idempotent and tested against empty, malformed, duplicate, and legacy values.

Settings synchronization allowlists exact names/types. Auto-freeze service enabled remains local-only as original. Cross-profile file chooser, contacts restriction, delay, and skip-foreground retain sync behavior.

---

## 10. UI architecture and UX improvements

### 10.1 Activity/service boundary

Activity owns ServiceConnection lifecycle. Introduce an Activity-retained `ProfileServices` holder with immutable snapshots of main/work binders and connection state. ViewModels depend on a repository/gateway that reads current service handles; they do not own raw binders or Activity references.

Connection states:

```text
BindingMain
ProbingWorkProfile
BindingWork
Ready
WorkProfileDisabled
WorkProfileMissing
ConnectionLost
```

Restart logic must avoid killing services owned by a newly restarted Activity.

### 10.2 App list

Compose LazyColumn with stable package/profile keys, explicit loading/error/empty states, search query StateFlow, pull-to-refresh, icon cache, and menu actions:

- launch;
- clone;
- freeze/unfreeze;
- auto-freeze toggle;
- uninstall;
- create unfreeze shortcut;
- cross-profile widget allowlist;
- cross-profile package interaction allowlist.

Preserve work-profile-only multi-select and linked package shortcuts. Show clear disabled/unsupported explanations instead of hiding actions.

### 10.3 Settings

Compose settings uses DataStore flows. Permission-dependent toggles are transactional: check permission first, open system settings when missing, update only after confirmed state. Disable File Shuttle on Android Q/Go constraints matching original behavior, with visible reason.

### 10.4 Setup UX

Compose replaces setup-wizard-lib visuals, not state behavior. Keep welcome, permissions, compatibility/MIUI warning, ready, progress, action-required, and failure states. Support system bars, accessibility labels, back navigation, process recreation, and `onNewIntent`.

### 10.5 UX verification

Manual checks on device:

- first-run setup;
- interrupted setup resume;
- disabled work profile message;
- search and refresh;
- frozen state visibility;
- batch actions;
- settings permission round-trip;
- clone/install progress and failure;
- DocumentsUI launch;
- shortcut creation.

---

## 11. Build and dependency policy

### 11.1 Build baseline

Lock versions only after running a clean compile on the target environment. Include Gradle wrapper. Do not assume Android Studio-only tooling. Verify:

- AIDL generation with Kotlin implementations;
- Hilt/KSP compatibility;
- Compose compiler plugin;
- release R8 rules;
- APK signing/installability.

### 11.2 Dependencies

Use only dependencies with proven callsites:

- AndroidX Core/Activity/Lifecycle;
- Compose Material3;
- DataStore;
- Coroutines;
- Hilt only if binding graph remains simpler than manual providers.

Do not add Coil, Navigation Compose, Preference KTX, or other libraries without an accepted callsite and build evidence. App icons can use the existing `Bitmap` AIDL callback and an in-memory cache; avoid allocating a dependency for a problem already handled by PackageManager.

Drop setup-wizard-lib, time-duration-picker, LocalBroadcastManager, ViewPager2, RecyclerView, XML layout dependencies only after feature parity is demonstrated.

### 11.3 Static checks

Before device testing:

- compile debug and release;
- run unit tests;
- run lint;
- verify manifest merger;
- inspect generated AIDL stubs;
- run R8 release build;
- install APK on clean emulator/device;
- compare exported components and authorities to baseline.

---

## 12. Test plan and phase gates

### Gate 0 — Contract Freeze
Pass only when:

- AIDL files byte-identical;
- manual Parcelable tests pass;
- action inventory complete;
- manifest diff reviewed;
- direction matrix tests pass;
- minSdk decision documented;
- migration key inventory complete.

### Gate 1 — Security and provisioning
Pass only when:

- bootstrap/auth tests pass;
- replay and wrong-profile requests fail;
- provisioning state machine tests pass;
- duplicate finalization is harmless;
- resume-after-process-death path works in device test.

### Gate 2 — IPC/services
Pass only when:

- all AIDL methods have implementations;
- callback exactly-once tests pass;
- PackageInstaller transaction tests pass;
- FD/provider timeout and binder-death tests pass;
- service scope and receiver cleanup tests pass.

### Gate 3 — UI parity
Pass only when:

- all original actions are reachable;
- app list, search, freeze, clone, uninstall, shortcuts, widget/package settings work;
- setup/settings/error states are accessible and actionable;
- no feature is removed without explicit scope decision.

### Gate 4 — Device evidence
Required on a physical or emulated device with managed-profile support:

1. clean install;
2. create Work Profile;
3. finalize and restart app;
4. disable/re-enable Work Profile;
5. list apps in both profiles;
6. clone system and non-system app;
7. install APK with split APKs;
8. uninstall app;
9. freeze/unfreeze and linked launch;
10. screen-off delayed auto-freeze and foreground skip;
11. File Shuttle/DocumentsUI read/write/thumbnail/create/delete;
12. cross-profile widgets/packages;
13. settings sync;
14. payment stub enable/disable if NFC available;
15. process death/restart;
16. upgrade from original Shelter data;
17. release APK install and uninstall sequence.

Record device model, Android version, OEM, profile state, exact steps, and observed result. Do not claim installability or parity without this evidence.

---

## 13. Implementation order

Do not fan out writers before Gate 0.

1. Contract inventory and diff.
2. Manual Parcelable implementations and tests.
3. Action constants, manifest, policy direction tests.
4. Auth bootstrap protocol and tests.
5. Provisioning state machine and tests.
6. PackageInstaller transaction manager and FD ownership.
7. Services and DocumentsProvider.
8. DataStore migration and repository.
9. Compose UI and service gateway.
10. Resources and release rules.
11. Static/build gates.
12. Device gates.
13. Security review and final parity review.

Parallel work begins only after shared contracts are frozen. Parallel slices may not edit AIDL, manifest, auth protocol, provisioning state machine, or Parcelable classes independently.

---

## 14. Current blockers

Implementation remains blocked until these artifacts exist and pass:

- contract inventory;
- exact Parcelable tests;
- corrected auth bootstrap design;
- provisioning state machine;
- install transaction ownership;
- bounded DocumentsProvider service acquisition;
- manifest/action direction diff;
- build baseline;
- managed-profile device test plan.

Reviewer report said NOT READY. This corrected plan remains NOT READY until Gate 0 evidence is produced.
