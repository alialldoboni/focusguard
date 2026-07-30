# FocusGuard

FocusGuard is an Android focus-protection app that monitors visible screen content and intervenes when it detects distracting content.

## Features

- Blocks configured social-media apps and short-form feeds.
- Evaluates visible YouTube content for productive or distracting signals.
- Uses on-device ML Kit OCR when accessibility text is unavailable.
- Shows a clear blocking overlay and returns the user to the Home screen.
- Runs as an Android accessibility service with a persistent background notification.
- Stores screen-time and intervention history locally with Room.
- Uses a dark forest-and-mint Material 3 interface built with Jetpack Compose.

## Privacy

Classification and OCR run on the Android device. The app does not request Android's Internet permission.

## Requirements

- Android 12 or newer (API 31+).
- Accessibility permission for FocusGuard.
- Notification permission on Android 13 or newer.

## Install the APK

1. Open the repository's **Releases** page.
2. Download `FocusGuard-v1.0.0.apk` or the ZIP package containing it.
3. Allow installation from the browser or file manager when Android asks.
4. Open FocusGuard.
5. Tap **Open accessibility**, select **FocusGuard**, and enable the service.
6. Allow notifications so the persistent background-status notification can appear.

The packaged APK is debug-signed for direct testing and installation.

## Build from source

Open the project in Android Studio with JDK 17, or run:

```bash
./gradlew assembleDebug
```

The APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Technology

- Kotlin
- Jetpack Compose and Material 3
- Android Accessibility Service
- ML Kit Text Recognition
- Room
- Kotlin Coroutines

## Important

FocusGuard makes local decisions from the text Android exposes and from OCR. Classification can occasionally produce false positives or false negatives, so review the behavior before relying on it.
