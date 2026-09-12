# MeetIn hybrid Android / iOS build

MeetIn is implemented as a native WebView shell around the existing hosted web application. The Android target remains unchanged and continues to provide the current WebView bridge, voice-note recording, file picker, downloads, notifications, OAuth navigation, and background data-sync service.

The new `ios/MeetIn.xcodeproj` target uses Swift, `WKWebView`, and a JavaScript message bridge with the same web-facing method names (`showNotification`, `startRecording`, `stopRecording`, `canRecordOgg`, `canRecordNative`, `downloadFile`, and `toast`). It supports hosted app loading, OAuth navigation, multi-type document selection, voice-note capture with the existing `onNativeVoiceNote` callback, local download sharing, and local notifications. Existing Android files and capabilities were not removed.

## Android build

```bash
./gradlew --no-daemon assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Release builds still use the existing Android Gradle configuration and require the normal signing configuration outside this repository.

## iOS build

An Apple machine with Xcode 16 or newer is required because Apple SDKs and code signing are not available on Linux.

```bash
xcodebuild \
  -project ios/MeetIn.xcodeproj \
  -scheme MeetIn \
  -sdk iphonesimulator \
  -configuration Debug \
  -derivedDataPath build/ios \
  CODE_SIGNING_ALLOWED=NO build
```

For a device or App Store archive, select a development team and provisioning profile in Xcode or supply the appropriate signing settings to `xcodebuild`.

## Platform boundaries

iOS does not expose public APIs equivalent to Android's SMS database access, installed-app inventory, device serial access, or an unrestricted always-on foreground TCP service. Those Android-only components remain in the Android target; the iOS target does not claim unsupported access or silently emulate it. If cross-device synchronization is required on iOS, it should be moved to an authenticated server-side service with explicit user consent and App Store-compliant background execution.

The iOS target also requests only microphone, media-selection, and notification access needed by the user-facing WebView features. Before distribution, configure signing, review privacy disclosures, and test OAuth redirects, microphone permission, file uploads, downloads, notifications, and the hosted URL on physical Android and iOS devices.
