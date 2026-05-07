# IGI SDK — Android

Embeds the Metabilia / IGI consumer experience (events, marketplace,
auctions, wishlist, mystery boxes, account history) into a host
Android app as a Jetpack Compose surface. Distributed as an AAR via
Maven Central.

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
    implementation 'io.metabilia:igi_sdk:4.0.2'
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
import com.igotitinc.sdk.config.IGIEnvironment

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Captures `applicationContext` for the SDK's internal DI
        // container. Must run before any other `IGIManager.getInstance()`
        // call in this process.
        IGIManager.getInstance().initialize(
            apiKey = BuildConfig.IGI_API_KEY,
            environment = IGIEnvironment.PRODUCTION,
            subDomain = "<SUBDOMAIN_FROM_METABILIA>",
            context = this,
        )

        // Active FCM token fetch — see "Push notifications" below.
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) IGIManager.getInstance().setDeviceToken(task.result)
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

The valid `IGIEnvironment` values are:

| Environment | Constant |
|---|---|
| Development | `IGIEnvironment.DEVELOPMENT` |
| Production | `IGIEnvironment.PRODUCTION` |

> **Legacy 3.x parity:** `IGIManager.getInstance()` is parameterless,
> mirroring the legacy Java SDK shape. Code carried over from a
> 3.x integration keeps compiling — only the `manager.initialize(...)`
> bootstrap call is new.

## Hosting the SDK UI

`IGIMainActivity` is the SDK's entry point — a 5-tab shell (events,
wishlist, mystery boxes, account, etc.) with all intra-SDK
navigation handled internally. Launch it via `Intent` from your
host wherever in your flow makes sense (post-login, splash, button
tap, push deep-link receiver — see **Push notifications** below):

```kotlin
import android.content.Intent
import com.igotitinc.sdk.ui.mainTabs.IGIMainActivity

startActivity(Intent(this, IGIMainActivity::class.java))
```

`IGIMainActivity` needs to be declared in your `AndroidManifest.xml`
— the canonical declaration (with the push-deeplink intent-filter)
is in **Required `AndroidManifest.xml`** below. It's a plain
`ComponentActivity` (no `@AndroidEntryPoint`) so it works regardless
of whether your host uses Hilt. Mirrors the iOS `IGIMainTabView`
1:1 — partners shipping both platforms get the same SDK surface on
each.

## Push notifications

Every IGI push notification is emitted server-side with
`click_action = "IGI_PUSH_DEEPLINK"`. When the user taps the
notification, Android dispatches an `Intent` with that action
carrying the push payload as extras — the host catches the tap by
declaring a matching intent-filter, and `IGIMainActivity` reads
the extras to route the user straight to the right item-detail
screen.

Wiring is host-owned across four pieces:

### 1. Tap-routing via intent-filter (cold-start + warm taps)

The simplest path is to declare the intent-filter directly on
`IGIMainActivity` in your `AndroidManifest.xml`:

```xml
<activity
    android:name="com.igotitinc.sdk.ui.mainTabs.IGIMainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="IGI_PUSH_DEEPLINK" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

`IGIMainActivity.onCreate` inspects `intent.extras` automatically,
calls `IGIManager.getInstance().shouldHandleRemoteMessage(...)` /
`handleRemoteMessage(...)`, and routes to the right screen — no glue
code needed in your host.
The SDK persists the user's auth via `EncryptedSharedPreferences`,
so the launched session resumes without re-prompting.

#### Optional: route through your own activity first

If you need to intercept the tap before launching `IGIMainActivity`
(custom auth check, analytics, conditional routing), declare the
intent-filter on a router activity in your host instead, then
forward the extras:

```kotlin
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.igotitinc.sdk.IGIManager
import com.igotitinc.sdk.ui.mainTabs.IGIMainActivity

class PushTapRouterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        if (extras != null) {
            val data = extras.keySet()
                .mapNotNull { key -> extras.getString(key)?.let { value -> key to value } }
                .toMap()
            if (IGIManager.getInstance().shouldHandleRemoteMessage(data)) {
                // ...your custom pre-launch logic (auth check, analytics, ...).
                // Then hand off to the SDK with extras forwarded:
                startActivity(
                    Intent(this, IGIMainActivity::class.java).putExtras(extras)
                )
            }
        }
        finish()
    }
}
```

```xml
<activity
    android:name=".PushTapRouterActivity"
    android:exported="true"
    android:noHistory="true">
    <intent-filter>
        <action android:name="IGI_PUSH_DEEPLINK" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

### 2. `FirebaseMessagingService` subclass (token + foreground delivery)

The intent-filter above handles tap-routing, but partners still
need an FCM service to (a) forward token rotations to the SDK,
and (b) optionally hand foreground push payloads to
`handleRemoteMessage` so the SDK updates internal state when a
push arrives while the app is open.

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.igotitinc.sdk.IGIManager

class FirebaseMessagingForwarder : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        IGIManager.getInstance().setDeviceToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) return
        val manager = IGIManager.getInstance()
        if (manager.shouldHandleRemoteMessage(data)) {
            manager.handleRemoteMessage(data)
        }
        // Non-IGI pushes: handle here if your app dispatches its own.
    }
}
```

```xml
<service
    android:name=".firebase.FirebaseMessagingForwarder"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

### 3. Active token fetch on every launch

`onNewToken` only fires when the FCM token genuinely rotates, and
Firebase caches tokens at the Google Play Services layer across app
uninstalls. A fresh dev install on the same emulator can quietly
reuse the previous token without firing the callback. In production
a token rotation that happens while the app process is dead can
leave your host without a fresh value.

Fetch explicitly in `Application.onCreate` (already shown in the
init snippet above). `IGIManager.getInstance().setDeviceToken(...)`
is idempotent and dedup-guarded — calling it on every launch is
cheap.

### 4. `POST_NOTIFICATIONS` runtime permission (Android 13+)

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

**No SDK-side theme configuration needed.** `IGIMainActivity` reads
the launched activity's Android XML theme — your host's
`<application android:theme="@style/AppTheme">` (or per-activity
override) — and bridges it into Compose's Material 3 `ColorScheme`
automatically. Whatever colors you've declared in `themes.xml` /
`colors.xml` for your own activities flow through to SDK screens.

This matches the legacy Java IGI SDK's behavior: partners declare
team branding in standard Android theme attributes, every screen
(host's and SDK's) inherits from the same source.

### What attributes get picked up

```xml
<style name="AppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
    <item name="colorPrimary">@color/team_primary</item>      <!-- → MaterialTheme.colorScheme.primary -->
    <item name="colorAccent">@color/team_accent</item>        <!-- → MaterialTheme.colorScheme.secondary (AppCompat fallback) -->
    <item name="android:colorBackground">@color/team_bg</item>  <!-- → MaterialTheme.colorScheme.background -->
</style>
```

For richer mappings (Material 3's `onPrimary` / `surface` /
`onSurface` / `error` / etc.), use a `Theme.Material3.*` parent and
declare the corresponding M3 attributes — the SDK reads all of them.
Slots not declared in your theme fall back to the IGI default
palette (Navy + Gold) — same fallback the SDK shipped before the
bridge.

### Light/dark detection

The bridge reads `?android:isLightTheme` and returns a
`lightColorScheme` or `darkColorScheme` accordingly. Hosts on a
DayNight theme parent get auto-switched alongside the system; hosts
on a fixed `Theme.AppCompat.Light.*` stay light regardless of the
system dark-mode setting.

### Cross-platform parity

Android `MaterialTheme.colorScheme` slots map to the same role
names as the iOS `IGI_KEY_*_COLOR` theme dictionary keys (primary,
secondary, onPrimary, background, etc.). Both SDKs source from the
same product palette per team — declared in
`themes.xml` / `colors.xml` on Android, in the
`IGIManager.themeDictionary` on iOS.

## Required `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application
    android:name=".MyApp"
    android:label="@string/app_name"
    android:theme="@style/AppTheme"
    ...>

    <!-- SDK entry point. `exported="true"` + the intent-filter let
         the OS deliver tapped IGI push notifications here directly
         (see "Push notifications" above). If your host doesn't ship
         IGI push deep-linking, drop the intent-filter and set
         `exported="false"`. -->
    <activity
        android:name="com.igotitinc.sdk.ui.mainTabs.IGIMainActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="IGI_PUSH_DEEPLINK" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>

    <!-- FCM forwarder — see "Push notifications" #2 above. -->
    <service
        android:name=".firebase.FirebaseMessagingForwarder"
        android:exported="false">
        <intent-filter>
            <action android:name="com.google.firebase.MESSAGING_EVENT" />
        </intent-filter>
    </service>
</application>
```

## Migrating from 3.x to 4.x

The 4.x release line replaces the Java + XML SDK that 3.x clients
shipped against with a Kotlin + Jetpack Compose rewrite. The
public API surface deliberately keeps the legacy 3.x entry-point
shapes — `IGIManager.getInstance()` is still parameterless, the
`initialize(apiKey, sdkMode: String, subDomain, context, callback)`
overload still works with the same string-mode constants, and the
`IGIAnalyticsListener` interface kept the same set of methods.
Most call sites need only an import-package update + a small
`IGIManagerCallback` syntax tweak.

### Quick checklist

- [ ] Update Maven dependency: `com.github.Metabilia-io:igi_sdk_android:3.x.x` → `io.metabilia:igi_sdk:4.x.x`. **Repository changed too** — drop the `maven { url "https://maven.pkg.github.com/..." }` block and use `mavenCentral()` instead. The GitHub Personal Access Token is no longer needed.
- [ ] Remove `gpr.user` / `gpr.key` (or any GitHub PAT credentials) from `~/.gradle/gradle.properties` if they were only used to download the SDK — `4.x.x` doesn't need them. Safe to keep them if other dependencies still pull from GitHub Packages.
- [ ] Update Kotlin imports: `igi_sdk.*` → `com.igotitinc.sdk.*`. Specific sub-packages too: `igi_sdk.activities.IGIMainActivity` → `com.igotitinc.sdk.ui.mainTabs.IGIMainActivity`, `igi_sdk.fragments.IGIManagerCallback` → `com.igotitinc.sdk.IGIManagerCallback`, `igi_sdk.model.IGIAnalyticsListener` → `com.igotitinc.sdk.analytics.IGIAnalyticsListener`.
- [ ] Same path update in your `AndroidManifest.xml` for the `IGIMainActivity` declaration if you have one.
- [ ] Replace `IGIManagerCallback(function = { ... })` constructor calls with bare SAM lambdas: `IGIManagerCallback { ... }`. The error parameter type is now `Throwable?` instead of `Error?`.
- [ ] If you implement `IGIAnalyticsListener`, replace placeholder `p0`/`p1` parameter names with the typed names + drop the `?` from primitives (legacy was `String?`/`Double?`/`Int?`; 4.x.x is non-nullable). The `trackEvent` method's payload type changed from `Bundle?` to `Map<String, Any>`.
- [ ] If you use `IGIManager.getInstance().privacyStatus` (Java property access) or `IGI_PRIVACY_STATUS.IGI_PRIVACY_STATUS_OPT_IN` constants, switch to the `getPrivacyStatus()` / `setPrivacyStatus(IGIPrivacyStatus.OptIn)` method + enum form.
- [ ] If you use `IGIManager.getInstance().isIGIPayload(extras)` (took a Bundle), it was removed. Project the Bundle to `Map<String, String>` and call `shouldHandleRemoteMessage(map)` instead — see code snippet below.
- [ ] Bump your host's `compileSdk` / `targetSdk` to **36** and `minSdkVersion` to **26**.
- [ ] If your `app/build.gradle` doesn't already exclude `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, add the `packaging { resources { excludes += [...] } }` block. okhttp 5 + jspecify 1 ship duplicate copies; AGP 8 won't merge them automatically.
- [ ] No Hilt-related changes — the SDK no longer requires `@HiltAndroidApp` on your `Application`. If your host uses Hilt for its own concerns, keep it; the SDK is independent.

### Source-level diffs

#### 1. Maven coordinate + repository

```diff
  // app/build.gradle
- implementation 'com.github.Metabilia-io:igi_sdk_android:3.4.2'
+ implementation 'io.metabilia:igi_sdk:4.x.x'
```

(Use the latest `4.x` published on Maven Central — see
**Install** above for the current version.)

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
      IGIManager.IGI_SDK_PRODUCTION_MODE,
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
4.x specifically so legacy call sites compile unchanged. New
code can switch to the typed `IGIEnvironment.PRODUCTION` enum
(also accepted by an overload of `initialize`).

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
   argument is non-null in 4.x. The SDK guarantees these are
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

> **Already on `IGISdk.shouldHandleRemoteMessage(data)`?**
> That static-form call shape is preserved in 4.x as
> `IGIManager.shouldHandleRemoteMessage(data)` (added in 4.0.2),
> so legacy 3.x call sites compile against 4.x with just a class
> rename:
>
> ```diff
> - IGISdk.shouldHandleRemoteMessage(data)
> + IGIManager.shouldHandleRemoteMessage(data)
> ```
>
> Java callers go through
> `IGIManager.Companion.shouldHandleRemoteMessage(data)`. If your
> 3.x integration is on `isIGIPayload(extras)` (`Bundle`-based),
> keep reading — the migration is below.

Legacy SDK exposed `IGIManager.getInstance().isIGIPayload(extras)`
which took a `Bundle` — partners called it from the activity that
caught the cold-start tap intent. The 4.x SDK exposes
`shouldHandleRemoteMessage(data: Map<String, String>?)` instead
(matches iOS `shouldHandleRemodeMessage`); the `Bundle` overload
is gone.

The simpler 4.x path: declare the
`<action android:name="IGI_PUSH_DEEPLINK" />` intent-filter
**directly on `IGIMainActivity`** in your manifest. `IGIMainActivity`
inspects its own intent extras in `onCreate` and calls
`shouldHandleRemoteMessage` / `handleRemoteMessage` internally — no
glue code needed in your host. See **Push notifications** above for
the canonical wiring.

If you still need a router activity (custom auth check, analytics,
etc.), update the legacy call site to project the Bundle to a Map
and use `shouldHandleRemoteMessage` first:

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

#### 8. AndroidManifest.xml — IGIMainActivity path

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

#### 9. `app/build.gradle` packaging exclude

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

### Validation reference

A complete worked migration from a real 3.x integration is in
[`IGISampleApp_android/`](./IGISampleApp_android/) — sample app
shipped alongside the SDK in this repo, consuming
`io.metabilia:igi_sdk:4.0.2` via Maven Central (the same way
partner hosts integrate). Files modified —
`IGISampleApplication.kt`, `MainActivity.kt`, `MainFragment.kt`,
`AnalyticsManager.kt`, `MyFirebaseMessagingService.kt`,
`AndroidManifest.xml` — plus the `packaging { ... }` block + the
`io.metabilia:igi_sdk` dependency line in `app/build.gradle` and
the `mavenCentral()`-only repo block in the root `build.gradle`.
Cover every change in this guide.

## Reporting issues

For SDK-level bugs / feature requests, file an issue on
`Metabilia-io/igi_sdk_android`. For credentials, environment
access, or API-key provisioning, contact Metabilia ops directly.
