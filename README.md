# CleanView Camera

An Android Camera2 sample that keeps camera controls on the phone and sends the camera preview to a separate Android presentation display.

## Important compatibility note
This app does not modify or remotely control the stock Camera app. You must use CleanView Camera to capture the live view.

The app requires the TV connection to appear to Android as a separate display in `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`. Some Smart View / Miracast implementations expose only a mirrored display. In that case, no third-party app can make the mirrored TV show a different interface using this approach. The app reports this condition.

## Build in Android Studio
1. Install Android Studio (recent stable release) and Android SDK Platform 35.
2. Open this folder as an existing Android Studio project.
3. Allow Gradle sync to complete.
4. Connect an Android phone, enable USB debugging, or build using Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Install the generated `app-debug.apk`.
6. Grant camera permission.
7. Connect the TV using Smart View.
8. Open CleanView Camera and tap **Find TV / Refresh display**.

## Current scope
- Camera preview on phone.
- Camera preview on a distinct presentation display, with no camera controls on TV.
- Front/rear camera switch.
- No audio, recording-to-file, remote TV controls, or network streaming.
- Uses Camera2 with two output surfaces; device camera hardware may reject simultaneous outputs on some models.

## Troubleshooting
- If the app says no separate presentation display was detected, Smart View is operating in mirroring-only mode on that phone/TV combination.
- If dual preview configuration fails, the phone may not support the selected concurrent output sizes. A production version should query supported stream combinations and select compatible sizes for the specific device.
- Keep the app in the foreground while using the TV preview.
