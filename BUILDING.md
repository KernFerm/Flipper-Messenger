# Building

## Requirements

- Official Flipper firmware 1.4.3+ compatible SDK, target f7/API 87.1
- uFBT, or the matching official firmware tree
- JDK 17 or newer (the verified environment used Microsoft OpenJDK 21)
- Android SDK 36 and build tools
- USB debugging for direct Android installation

## Flipper

```powershell
Set-Location .\flipper
ufbt
```

Output: `flipper/dist/flipper_messenger.fap`. Install/test an attached device with `ufbt launch`. With the full firmware tree, place `flipper` under `applications_user/flipper_messenger` and build the matching external application target.

## Android debug build

Create `android/local.properties` with your SDK path (this file is ignored), then:

```powershell
Set-Location .\android
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

For distribution, create your own protected signing key and use Android Studio or a local, uncommitted signing configuration. Never commit a keystore, passwords, `local.properties`, or private test data.

## Android signed release build

Keep the publisher keystore and `android/keystore.properties` private and backed up. They are ignored by Git. Losing the key prevents future APKs from updating existing installations.

The local properties file has this format:

```properties
storeFile=release-signing/flipper-messenger-release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=flipper-messenger
keyPassword=YOUR_PRIVATE_PASSWORD
```

Build and validate the signed, optimized release APK with:

```powershell
Set-Location .\android
.\gradlew.bat clean testReleaseUnitTest lintRelease assembleRelease
```

The signed result is `android/app/build/outputs/apk/release/app-release.apk`. Verify it before publishing with Android SDK `apksigner verify --verbose --print-certs`.

