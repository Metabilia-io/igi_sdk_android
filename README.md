# IGI SDK — Android

Embeds the Metabilia / IGI consumer experience (events, marketplace,
auctions, wishlist, mystery boxes, account history) into a host
Android app as a Jetpack Compose surface. Distributed as an AAR via
Maven (GitHub Packages).

> **Companion guide for iOS:** see
> [`Metabilia-io/igi_sdk_ios` → `README.md`](https://github.com/Metabilia-io/igi_sdk_ios/blob/main/README.md).
> Both platforms expose the same public types, use byte-identical
> analytics-event names and theming-token keys, and ship in lockstep
> at the same `MAJOR.MINOR.PATCH` version (currently `4.0.0`).

## Requirements

| | |
|---|---|
| `compileSdk` / `targetSdk` | **36** |
| `minSdkVersion` | **26** (Android 8.0) |
| Kotlin | **2.1.20+** |
| Android Gradle Plugin | **8.9+** |
| Java toolchain | **17** |
| Hilt | **Not required.** SDK runs its own internal DI container; partner hosts can use Hilt or not. |

## Install

The SDK is published to **Maven Central** under
`io.metabilia:igi_sdk`. No authentication, no Personal Access Token,
no per-engineer credential provisioning — `mavenCentral()` is enough.

```groovy
// Project-level build.gradle (or settings.gradle)
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle
dependencies {
    implementation 'io.metabilia:igi_sdk:4.0.0'
    // ...your existing deps (Firebase, Branch, etc.)
}
```

That single line pulls in everything the SDK exposes publicly:
Compose UI, Material 3, Coroutines, Lifecycle-Compose, Navigation,
activity-compose. All internal deps (Retrofit, Moshi, OkHttp,
Pusher, Stripe, Coil, EncryptedSharedPreferences, DataStore) are
declared `implementation` in the SDK's POM and don't appear on
your compile classpath. **No Hilt plugin or annotation processors
required.**

## Initialization

Initialize the SDK exactly once at process start, in your
`Application.onCreate`. The SDK requires an API key, environment,
and subdomain — all three come from Metabilia ops.

```kotlin
import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.igotitinc.sdk.IGIManager
import com.igotitinc.sdk.IGISdk
import com.igotitinc.sdk.config.IGIEnvironment

class MyApp : Application() {

    private val manager: IGIManager by lazy { IGIManager.getInstance() }
    private val sdk: IGISdk get() = manager.sdk

    override fun onCreate() {
        super.onCreate()

        // Captures `applicationContext` for the SDK's internal DI
        // container and runs `IGISdk.initialize`. Must run before
        // any other manager / sdk access in this process.
        manager.initialize(
            apiKey = BuildConfig.IGI_API_KEY,
            environment = IGIEnvironment.PRODUCTION,
            subDomain = "<SUBDOMAIN_FROM_METABILIA>",
            context = this,
        )

        // Active FCM token fetch — see "Push notifications" below.
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) sdk.setDeviceToken(task.result)
        }
    }
}
```

Register the Application class in your `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ...>
```

The three valid `IGIEnvironment` values are:

| Environment | Constant |
|---|---|
| Development | `IGIEnvironment.DEVELOPMENT` |
| Sandbox / Staging | `IGIEnvironment.SANDBOX` |
| Production | `IGIEnvironment.PRODUCTION` |

> **Legacy 3.x parity:** `IGIManager.getInstance()` is parameterless,
> mirroring the legacy Java SDK shape. Code carried over from a
> 3.x integration keeps compiling — only the `manager.initialize(...)`
> bootstrap call is new.

## Hosting the SDK UI

Two ways to surface the SDK's UI in your host app:

### Option A — Compose: embed `IGIMainTabs`

For hosts already on Compose. Wrap in your own `MaterialTheme` with
your team's branded colors — the SDK reads
`MaterialTheme.colorScheme.*` tokens directly:

```kotlin
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.igotitinc.sdk.IGIManager
import com.igotitinc.sdk.ui.mainTabs.IGIMainTabs
import com.igotitinc.sdk.ui.mainTabs.IGIMainTabsCallbacks

class MainActivity : ComponentActivity() {

    private val sdk by lazy { IGIManager.getInstance().sdk }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = myTeamColorScheme) {
                IGIMainTabs(
                    callbacks = IGIMainTabsCallbacks(
                        onClose = { finish() },
                        // ...other host-routed lambdas
                    ),
                )
            }
        }
    }
}
```

`IGIMainTabsCallbacks` is a typed inventory of every lambda the
SDK routes back to the host (close, deep-link out, sign-out, etc.).
KDoc on each field describes where the equivalent surfaces on iOS.

### Option B — UIKit-equivalent: launch `IGIMainActivity` via Intent

For hosts that want the SDK to own its own Activity (drop-in
pattern matching the legacy Java SDK):

```kotlin
import com.igotitinc.sdk.ui.mainTabs.IGIMainActivity

startActivity(Intent(this, IGIMainActivity::class.java))
```

`IGIMainActivity` is a plain `ComponentActivity` (no
`@AndroidEntryPoint`); it owns the 5-tab shell + intra-SDK
navigation. Mirrors iOS `IGIMainTabView` and the legacy Java SDK's
`IGIMainActivity` so partners migrating from the legacy SDK keep
their `Intent` call site intact.

## Push notifications

The SDK consumes FCM tokens to deliver item-update / auction
realtime pushes. Wiring is host-owned because the partner already
manages Firebase. The SDK only needs the FCM token forwarded once
per session, plus the incoming `RemoteMessage` payloads handed
through.

**All three pieces are necessary** — missing any one silently
degrades push delivery in a different scenario.

### 1. `FirebaseMessagingService` subclass

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.igotitinc.sdk.di.getIGISdk

class FirebaseMessagingForwarder : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        getIGISdk(applicationContext).setDeviceToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) return
        val sdk = getIGISdk(applicationContext)
        if (sdk.shouldHandleRemoteMessage(data)) {
            sdk.handleRemoteMessage(data)
        }
        // Non-IGI pushes: handle here if your app dispatches its own.
    }
}
```

Register in `AndroidManifest.xml`:

```xml
<service
    android:name=".firebase.FirebaseMessagingForwarder"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

### 2. Active token fetch on every launch

`onNewToken` only fires when the FCM token genuinely rotates, and
Firebase caches tokens at the Google Play Services layer across app
uninstalls. A fresh dev install on the same emulator can quietly
reuse the previous token without firing the callback. In production
a token rotation that happens while the app process is dead can
leave your host without a fresh value.

Fetch explicitly in `Application.onCreate` (already shown in the
init snippet above). `IGISdk.setDeviceToken` is idempotent and
dedup-guarded — calling it on every launch is cheap.

### 3. `POST_NOTIFICATIONS` runtime permission (Android 13+)

Required for any notification to render. Prompt the user at a
sensible moment in your UX (post-login, post-onboarding, or first
launch). The SDK does **not** prompt — it only consumes the token
once it exists.

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

```kotlin
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build

@Composable
fun NotificationPermissionPrompt() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — re-prompt next launch if needed */ }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

## Analytics

The SDK is provider-agnostic. It emits string-keyed events through
an `IGIAnalyticsListener` interface; the host implements that and
forwards to Firebase / Mixpanel / Amplitude / etc. Event names and
payload keys are stable across releases — see
[`analytics/IGIAnalyticsEvents.kt`](src/main/kotlin/com/igotitinc/sdk/analytics/IGIAnalyticsEvents.kt)
for the constants.

```kotlin
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.igotitinc.sdk.analytics.IGIAnalyticsListener

class AnalyticsBridge(private val analytics: FirebaseAnalytics) : IGIAnalyticsListener {
    override fun trackEvent(eventName: String, parameters: Map<String, Any>?) {
        val bundle = Bundle().apply {
            parameters?.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Double -> putDouble(k, v)
                    is Boolean -> putBoolean(k, v)
                }
            }
        }
        analytics.logEvent(eventName, bundle)
    }
}

// Register after manager.initialize(...) succeeds:
IGIManager.getInstance().setAnalyticsListener(
    AnalyticsBridge(Firebase.analytics)
)
```

## Theming

The SDK never wraps itself in a theme — every composable reads
`MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*`
tokens directly. Wrap `IGIMainTabs` (or the host activity rendering
the SDK's UI) in your own `MaterialTheme`:

```kotlin
val myTeamColorScheme = lightColorScheme(
    primary = Color(0xFF002244),    // team primary
    secondary = Color(0xFFC60C30),  // team secondary
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
    // ...
)

setContent {
    MaterialTheme(colorScheme = myTeamColorScheme) {
        IGIMainTabs(callbacks = ...)
    }
}
```

Cross-platform parity: the Android `MaterialTheme.colorScheme`
mapping uses the same role names (`primary`, `secondary`,
`onPrimary`, etc.) as the iOS `IGI_KEY_*_COLOR` theme dictionary
keys. Both SDKs source from the same product palette per team.

## Required `AndroidManifest.xml`

In addition to the service registration and permissions shown
above:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application
    android:name=".MyApp"
    android:label="@string/app_name"
    android:theme="@style/AppTheme"
    ...>
    <!-- SDK activity (optional — only if launching via Intent) -->
    <activity
        android:name="com.igotitinc.sdk.ui.mainTabs.IGIMainActivity"
        android:exported="false" />
</application>
```

## Deep links / Universal links

Deep-link handling is host-owned (Branch SDK in our reference host;
your app may use a different provider). The SDK does not register
URL schemes or app-link intent filters. The single SDK touchpoint
is forwarding the resolved item identifier:

```kotlin
// After Branch (or your URL handler) resolves the deep link to
// an `event_item_id`:
sdk.handleDeeplinkUrl(deeplink)
```

For pushes, `handleRemoteMessage` (see "Push notifications" above)
covers the same routing.

## Versioning

`MAJOR.MINOR.PATCH` semver, with a build-number suffix
(`+yyMMddNN`) baked into the AAR. `4.0.0` is the first release of
the Kotlin-canonical SDK; prior `3.x` releases shipped the
now-removed Java + XML implementation and are not source-compatible
with `4.x`.

Android and iOS ship in lockstep — the iOS counterpart
(`Metabilia-io/igi_sdk_ios` SPM, library product `igi_sdk`) is
tagged at the same `MAJOR.MINOR.PATCH` for every release, including
`4.0.0`. Partners shipping both platforms should pin both at the
same version.

## Migrating from 3.x to 4.0.0

The 4.0.0 release replaces the Java + XML SDK that 3.x clients
shipped against with a Kotlin + Jetpack Compose rewrite. The
public API surface deliberately keeps the legacy 3.x entry-point
shapes — `IGIManager.getInstance()` is still parameterless, the
`initialize(apiKey, sdkMode: String, subDomain, context, callback)`
overload still works with the same string-mode constants, and the
`IGIAnalyticsListener` interface kept the same set of methods.
Most call sites need only an import-package update + a small
`IGIManagerCallback` syntax tweak.

### Quick checklist

- [ ] Update Maven dependency: `com.github.Metabilia-io:igi_sdk_android:3.x.x` → `io.metabilia:igi_sdk:4.0.0`. **Repository changed too** — drop the `maven { url "https://maven.pkg.github.com/..." }` block and use `mavenCentral()` instead. The GitHub Personal Access Token is no longer needed.
- [ ] Remove `gpr.user` / `gpr.key` (or any GitHub PAT credentials) from `~/.gradle/gradle.properties` if they were only used to download the SDK — `4.0.0` doesn't need them. Safe to keep them if other dependencies still pull from GitHub Packages.
- [ ] Update Kotlin imports: `igi_sdk.*` → `com.igotitinc.sdk.*`. Specific sub-packages too: `igi_sdk.activities.IGIMainActivity` → `com.igotitinc.sdk.ui.mainTabs.IGIMainActivity`, `igi_sdk.fragments.IGIManagerCallback` → `com.igotitinc.sdk.IGIManagerCallback`, `igi_sdk.model.IGIAnalyticsListener` → `com.igotitinc.sdk.analytics.IGIAnalyticsListener`.
- [ ] Same path update in your `AndroidManifest.xml` for the `IGIMainActivity` declaration if you have one.
- [ ] Replace `IGIManagerCallback(function = { ... })` constructor calls with bare SAM lambdas: `IGIManagerCallback { ... }`. The error parameter type is now `Throwable?` instead of `Error?`.
- [ ] If you implement `IGIAnalyticsListener`, replace placeholder `p0`/`p1` parameter names with the typed names + drop the `?` from primitives (legacy was `String?`/`Double?`/`Int?`; 4.0.0 is non-nullable). The `trackEvent` method's payload type changed from `Bundle?` to `Map<String, Any>`.
- [ ] If you use `IGIManager.getInstance().privacyStatus` (Java property access) or `IGI_PRIVACY_STATUS.IGI_PRIVACY_STATUS_OPT_IN` constants, switch to the `getPrivacyStatus()` / `setPrivacyStatus(IGIPrivacyStatus.OptIn)` method + enum form.
- [ ] If you use `IGIManager.shouldHandleRemoteMessage(data)` as a static call, change to `IGIManager.getInstance().shouldHandleRemoteMessage(data)` — it's an instance method now.
- [ ] If you use `IGIManager.getInstance().isIGIPayload(extras)` (took a Bundle), it was removed. Project the Bundle to `Map<String, String>` and call `shouldHandleRemoteMessage(map)` instead — see code snippet below.
- [ ] Bump your host's `compileSdk` / `targetSdk` to **36** and `minSdkVersion` to **26**.
- [ ] If your `app/build.gradle` doesn't already exclude `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, add the `packaging { resources { excludes += [...] } }` block. okhttp 5 + jspecify 1 ship duplicate copies; AGP 8 won't merge them automatically.
- [ ] No Hilt-related changes — the SDK no longer requires `@HiltAndroidApp` on your `Application`. If your host uses Hilt for its own concerns, keep it; the SDK is independent.

### Source-level diffs

#### 1. Maven coordinate + repository

```diff
  // app/build.gradle
- implementation 'com.github.Metabilia-io:igi_sdk_android:3.4.2'
+ implementation 'io.metabilia:igi_sdk:4.0.0'
```

```diff
  // Project-level build.gradle
  allprojects {
      repositories {
          google()
          mavenCentral()
-         maven {
-             url = uri("https://maven.pkg.github.com/Metabilia-io/igi_sdk_android")
-             credentials {
-                 username = project.findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR') ?: ''
-                 password = project.findProperty('gpr.key') ?: System.getenv('GITHUB_TOKEN') ?: ''
-             }
-         }
      }
  }
```

Three changes here:

- **groupId** moves from `com.github.Metabilia-io` (a JitPack-style
  coordinate that GitHub Packages also accepted) to `io.metabilia`
  (Sonatype-verified namespace owned by Metabilia, Inc.).
- **artifactId** moves from `igi_sdk_android` (legacy Java) to
  `igi_sdk` (canonical Kotlin).
- **Repository** moves from GitHub Packages (`maven.pkg.github.com`)
  to Maven Central (`mavenCentral()`). The GitHub Personal Access
  Token previously required for download is no longer needed.

The GitHub repo name `Metabilia-io/igi_sdk_android` stays the same —
only the published-artifact coordinate moves.

#### 2. Repository credentials (security)

Maven Central downloads are anonymous — there are no credentials to
configure on the consumer side. Drop the entire
`maven { url = ... credentials { ... } }` block from your project's
`build.gradle`. If you previously hardcoded a GitHub PAT in the
credentials block, rotate it via GitHub → Settings → Developer
Settings → Personal access tokens → Revoke, and remove the block in
the same commit.

#### 3. Imports

```diff
- import igi_sdk.activities.IGIMainActivity
- import igi_sdk.fragments.IGIManagerCallback
- import igi_sdk.model.IGIManager
- import igi_sdk.model.IGIAnalyticsListener
- import igi_sdk.model.IGIManager.IGI_PRIVACY_STATUS
+ import com.igotitinc.sdk.IGIManager
+ import com.igotitinc.sdk.IGIManagerCallback
+ import com.igotitinc.sdk.ui.mainTabs.IGIMainActivity
+ import com.igotitinc.sdk.analytics.IGIAnalyticsListener
+ import com.igotitinc.sdk.privacy.IGIPrivacyStatus
```

The legacy package was the root-level `igi_sdk` (no domain prefix
— unconventional for Android). The new package uses the standard
`com.igotitinc.sdk` root. Sub-packages also reorganized: model
types moved under `data.model`, UI surfaces under `ui.<feature>`,
privacy types under `privacy`. Your IDE's "organize imports" /
find-replace handles this in one pass per file.

#### 4. Initialization

The same five-arg signature continues to work. The only edit is
the `IGIManagerCallback` construction syntax + the error type:

```diff
  IGIManager.getInstance().initialize(
      apiKey,
      IGIManager.IGI_SDK_SANDBOX_MODE,
      subDomain,
      this,
-     IGIManagerCallback(function = { _, error: Error? ->
-         if (error != null) Log.e("IGI", error.localizedMessage!!)
-     })
+     IGIManagerCallback { _, error ->
+         if (error != null) Log.e("IGI", error.localizedMessage ?: "unknown")
+     }
  )
```

`IGIManagerCallback` is now a Kotlin `fun interface`, so the
named-arg `function = { ... }` constructor call shape goes away in
favour of a bare SAM lambda. The error parameter type changed
from the legacy SDK's `Error` class to standard `Throwable?` —
existing `error.localizedMessage` access keeps working since
`Throwable` exposes that too. The `IGIManager.IGI_SDK_*_MODE`
string constants are preserved on `IGIManager`'s companion in
4.0.0 specifically so legacy call sites compile unchanged. New
code can switch to the typed `IGIEnvironment.SANDBOX` enum (also
accepted by an overload of `initialize`).

#### 5. `IGIAnalyticsListener`

The interface keeps the same set of methods (`trackPurchase`,
`trackBid`, `trackPromotion`, `trackAddPayment`,
`trackAddShipping`, `trackItemListSelection`, `trackItemSelection`,
`trackEvent`). Three signature-level changes:

```diff
  class AnalyticsManager : IGIAnalyticsListener {
-     override fun trackPurchase(
-         p0: String?, p1: String?, p2: String?, p3: String?,
-         p4: String?, p5: Double?, p6: Int?, p7: Double?, p8: String?,
-     ) { ... }
+     override fun trackPurchase(
+         transactionId: String,
+         userId: String,
+         itemId: String,
+         itemName: String,
+         itemCategory: String,
+         price: Double,
+         quantity: Int,
+         total: Double,
+         currencyCode: String,
+     ) { ... }
      // ...same shape change for trackBid / trackPromotion / etc.

-     override fun trackEvent(eventName: String?, attributes: Bundle?) { ... }
+     override fun trackEvent(eventName: String, attributes: Map<String, Any>) { ... }
  }
```

Three things changed:

1. **Typed parameter names.** Legacy showed `p0..p8` because the
   Java `.class` file lost parameter names; Kotlin sees the
   actual names now.
2. **Non-nullable types.** Every `String` / `Double` / `Int`
   argument is non-null in 4.0.0. The SDK guarantees these are
   populated before dispatching, so no defensive null-checks
   needed in the listener.
3. **`trackEvent` payload type.** `Bundle?` → `Map<String, Any>`.
   Convert to a Bundle inside the method if your provider needs
   one (Firebase Analytics does):
   ```kotlin
   val bundle = Bundle().apply {
       attributes.forEach { (k, v) ->
           when (v) {
               is String -> putString(k, v)
               is Int -> putInt(k, v)
               is Long -> putLong(k, v)
               is Double -> putDouble(k, v)
               is Boolean -> putBoolean(k, v)
           }
       }
   }
   ```

#### 6. Privacy status

Property → method, Java-style constants → typed Kotlin enum:

```diff
- val privacyStatus = IGIManager.getInstance().privacyStatus
- IGIManager.getInstance().privacyStatus =
-     IGI_PRIVACY_STATUS.IGI_PRIVACY_STATUS_OPT_IN
+ val privacyStatus = IGIManager.getInstance().getPrivacyStatus()
+ IGIManager.getInstance().setPrivacyStatus(IGIPrivacyStatus.OptIn)
```

`IGIPrivacyStatus` is a two-value enum (`OptIn` / `OptOut`)
matching iOS naming.

#### 7. Cold-start push handling: replacing `isIGIPayload(Bundle)`

Legacy SDK exposed `IGIManager.getInstance().isIGIPayload(extras)`
which took a `Bundle` — useful for cold-start launches where the
notification tap puts the payload on the Activity's launching
Intent extras. The 4.0.0 SDK exposes the canonical
`shouldHandleRemoteMessage(data: Map<String, String>?)` instead
(matches iOS `shouldHandleRemodeMessage`), so project the Bundle
to a Map first:

```diff
- if (IGIManager.getInstance().isIGIPayload(extras)) {
+ val data = extras.keySet()
+     .mapNotNull { key -> extras.getString(key)?.let { value -> key to value } }
+     .toMap()
+ if (IGIManager.getInstance().shouldHandleRemoteMessage(data)) {
      val i = Intent(this, IGIMainActivity::class.java)
      i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      i.putExtras(extras)
      startActivity(i)
  }
```

#### 8. `shouldHandleRemoteMessage` is now an instance method

```diff
  override fun onMessageReceived(remoteMessage: RemoteMessage) {
      if (remoteMessage.notification != null) {
-         if (IGIManager.shouldHandleRemoteMessage(remoteMessage.data)) {
+         if (IGIManager.getInstance().shouldHandleRemoteMessage(remoteMessage.data)) {
              IGIManager.getInstance().handleRemoteMessage(remoteMessage.data)
          }
      }
  }
```

#### 9. AndroidManifest.xml — IGIMainActivity path

If you declared a `<activity>` for `IGIMainActivity` in your
manifest (typical when using `tools:replace` to override the
SDK's default theme on this activity), update the
fully-qualified name:

```diff
  <activity
-     android:name="igi_sdk.activities.IGIMainActivity"
+     android:name="com.igotitinc.sdk.ui.mainTabs.IGIMainActivity"
      android:label="Metabilia"
      android:theme="@style/AppTheme"
      tools:replace="android:theme"
      android:launchMode="singleTask"
      android:screenOrientation="portrait" />
```

#### 10. `app/build.gradle` packaging exclude

Required for hosts on AGP 8.x — the new SDK's transitive deps
(okhttp 5 + jspecify 1) ship duplicate
`META-INF/versions/9/OSGI-INF/MANIFEST.MF` files that AGP refuses
to merge automatically:

```diff
  android {
      // ...

+     packaging {
+         resources {
+             excludes += [
+                 "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
+             ]
+         }
+     }
  }
```

### Activity / Fragment hosting (if you embedded individual screens)

The legacy SDK shipped 46 `Fragment` subclasses you could embed in
your host's `FragmentManager`. The 4.0.0 SDK ships:

- **`IGIMainActivity`** — drop-in `Intent`-launchable container,
  same name as the legacy entry point (only the package path
  changes per the import diff above).
- **`IGIMainTabs` composable** — the same UI as a Compose
  destination embeddable inside your own `NavHost`.

If you were calling individual Fragment subclasses by name from
your host, those entry points don't exist as Fragments anymore —
they're internal Compose screens reached through in-SDK
navigation. Most legacy hosts only used `IGIMainActivity` (the
common case), so the migration is just the rename + intent pass-
through. If you do depend on a specific screen entry point that
isn't reachable through `IGIMainActivity`'s tabs, file an issue
on `Metabilia-io/igi_sdk_android` and we'll surface it as a
public composable.

### Behavior changes (no code edits required)

| Layer | 3.x | 4.0.0 |
|---|---|---|
| Token storage | Plaintext `SharedPreferences` (`IGotItUserSessionKey`) | `EncryptedSharedPreferences` via `IGITokenStore`. One-shot migration on first 4.x launch reads the legacy key, copies into Encrypted store, deletes plaintext. |
| HTTP auth | `?access_token=…` query parameter | `Authorization: <token>` header (raw token, no `Bearer ` prefix). |
| Identity headers | `igi_sdk_version` (underscores) | `igi-sdk-version` (hyphens). nginx and similar proxies strip underscore-named headers by default. |
| Environments | Two: staging + prod | **Three**: `IGIEnvironment.DEVELOPMENT`, `.SANDBOX`, `.PRODUCTION`. |
| UI implementation | `Fragment` + XML | Jetpack Compose. `IGIMainActivity` wraps it for `Intent`-style hosting. |

### APIs that haven't changed (call sites unchanged after the import update)

- `IGIManager.getInstance()` — parameterless singleton accessor
- `IGIManager.getInstance().setAnalyticsListener(...)`
- `IGIManager.getInstance().setDeviceToken(...)`
- `IGIManager.getInstance().handleRemoteMessage(...) → Boolean`
- `IGIManager.getInstance().handleDeeplinkUrl(...)`
- `IGIManager.IGI_SDK_DEV_MODE` / `IGI_SDK_SANDBOX_MODE` / `IGI_SDK_PRODUCTION_MODE` string constants (preserved on the companion for legacy compat)
- 70+ legacy callback-style methods on `IGIManager` (`login`, `bidOnItem`, `signUp`, etc.) — preserved via the callback-style facade so legacy call sites keep compiling.

### Validation reference

A complete worked migration from a real 3.x integration is in
`IGISampleApp_android/` (in the parent workspace). Six files
modified — `IGISampleApplication.kt`, `MainActivity.kt`,
`MainFragment.kt`, `AnalyticsManager.kt`,
`MyFirebaseMessagingService.kt`, `AndroidManifest.xml` — plus the
`packaging { ... }` block + Maven dependency line in
`app/build.gradle` and the credentials swap in the root
`build.gradle`. Cover every change in this guide.

## Reporting issues

For SDK-level bugs / feature requests, file an issue on
`Metabilia-io/igi_sdk_android`. For credentials, environment
access, or API-key provisioning, contact Metabilia ops directly.

---

> **Security note (publishers only):** consumers don't need any
> credentials — Maven Central downloads are anonymous. Publishing
> the SDK to Maven Central does require Sonatype Central Portal
> tokens + a GPG signing keypair; both belong in
> `~/.gradle/gradle.properties` (gitignored), never in any file
> committed to the repo. During the GitHub-Packages era (pre-Phase D),
> two leaked GitHub PATs were rotated during the Android distribution
> prep (2026-05-02 — one in the legacy SDK module's `build.gradle`,
> one in `IGISampleApp_android/build.gradle`); both files use the
> env-var pattern now and the PATs themselves are obsolete since the
> repo coordinate moved to `mavenCentral()` in Phase D.
