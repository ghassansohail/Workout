# Form Coach

Form Coach is a native Android starter app that uses the phone camera to estimate body landmarks and give live exercise cues. Pose processing stays on the device; the app does not upload or save camera frames.

## Included in this first version

- Front-camera live preview
- On-device pose tracking with ML Kit
- Rep counter, measured joint angle, and coaching cue for squats, push-ups, and biceps curls
- Spoken coaching cues, enabled by default, with a voice toggle and cooldown
- Camera permission and missing-pose guidance

The coaching checks use a few landmark angles and are a starting point, not a medical or professional assessment. Spoken cues use Android's installed text-to-speech voice and are rate-limited to avoid constant repetition. Checks are most reliable with one person in frame, good lighting, the full body visible, and a side view for these exercises. Push-up checking uses shoulder, hip, ankle, and elbow landmarks; curl checking estimates the visible elbow angle.

## Open and run

Open this folder in Android Studio with Android SDK 35 and Java 17 installed. The repo includes a Gradle 8.9 wrapper. From the command line, create `local.properties` with `sdk.dir=<path to your Android SDK>`, then run `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Run the `app` configuration on an Android 6.0+ phone or emulator with a camera and grant camera access when prompted. For a real workout, a physical device is recommended.

The project uses Google ML Kit's bundled pose-detection SDK in stream mode and CameraX's latest-frame analysis strategy. See [Google's Android pose detection guide](https://developers.google.com/ml-kit/vision/pose-detection/android) for SDK details.

## Build an APK without installing Android tools on your computer

The repository includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`. To use it, create a GitHub repository and upload this project's files, including that workflow. GitHub can upload a folder through the browser, with a 25 MiB per-file limit. In the repository, open **Actions → Build Android APK → Run workflow**. When the run finishes, download the `form-coach-debug-apk` artifact and extract `app-debug.apk` on your phone. The workflow artifact is kept for 14 days.

The APK is a debug build for personal use. Android may ask you to allow installation from the browser or file manager you use to open it.
