# BLACK HOLE

## 1.1.1 extraction recovery

The downloader still runs on the phone: no hosting account, payment card, or API key.
Metadata now uses tolerant JSON parsing instead of the library's fixed metadata model.
On analysis failure the app attempts an official stable yt-dlp update and retries once;
update attempts are limited to once per six hours. An update requires internet access
to GitHub, but a successful download does not wait for an update check.
Analysis now has socket/retry limits and a cancellable process ID.
Tap an extraction error to inspect/copy its diagnostic text (URLs are redacted).
Review diagnostics before sharing them, since they can still include video identifiers.

Validation for this patch: local backend policy tests and whitespace checks passed.
Local Android compilation was blocked by unavailable Gradle distribution network access.
Use the branch's GitHub Actions compile/lint and device tests before installing.
The direct-MP4 fixture does not establish support for a social-platform link.

BLACK HOLE is a minimal Android 9+ public-video downloader. Version 1.1.0 runs yt-dlp and FFmpeg on the phone, so the app does not need Render, a paid extraction API, `backend-url.txt`, or `BLACK_HOLE_API_URL`. The existing `backend/` remains in the repository for history and optional experimentation; the Android download path does not call it.

## What the app does

- Accepts a public HTTP(S) video page from the clipboard or Android Share Sheet.
- Uses embedded yt-dlp to select the best available H.264/AAC MP4 first, then MP4 fallbacks.
- Uses embedded FFmpeg to merge separate video and audio streams without upscaling or intentional re-encoding.
- Validates that the result contains playable video and audio before publishing it.
- Saves to public `Download/BLACK HOLE`, and records the successful item in HISTORY.
- Supports open/play, share, and delete from history.

“Best available” means the best stream exposed by the platform, not a guarantee of the creator's original upload. Private, login-required, DRM-protected, geo-blocked, removed, or extractor-incompatible links can fail. Platforms change frequently, so one successful sample never guarantees every link.

## Build and verification

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

GitHub Actions uploads `BLACK-HOLE-1.1.0-direct-APK`. Its build record contains byte size, SHA-256, commit SHA, signature output, manifest metadata, and the universal APK. Device jobs install and launch that exact build on API 28, 29, and 36, then exercise the UI, Share Sheet input, a real local MP4 with audio, MediaStore/public storage, 100%-only-after-save, history, and deletion.

The APK intentionally contains native Python/yt-dlp/FFmpeg runtimes for arm64-v8a, armeabi-v7a, x86, and x86_64. It is therefore much larger than the old server-dependent diagnostic APK. This is the tradeoff for removing hosting and monthly server dependency.

Minimum Android: 9 / API 28. Target and compile SDK: Android 16 / API 36. Network behavior still depends on the carrier/Wi-Fi DNS, regional platform access, platform rate limits, and the public link itself; no build can truthfully guarantee every network or every phone.

## Install and use

1. Download and extract the `BLACK-HOLE-1.1.0-direct-APK` artifact from a green Actions run.
2. Install `BLACK-HOLE-1.1.0-direct.apk`. Allow installation from your file manager if Android asks.
3. Copy a public video link or share it to BLACK HOLE.
4. Open BLACK HOLE and tap the black hole once.
5. Keep enough free storage for the temporary download plus the final saved copy. Completion is shown only after validation and publication.
6. Find the result in `Downloads/BLACK HOLE`. Some Gallery apps do not display the Downloads collection, so use a file manager if needed.

Android 9 asks for legacy storage access. Android 10+ uses scoped MediaStore. Notification permission on Android 13+ is optional for the visible in-app flow, but enabling it gives foreground progress and cancellation controls.

## Signing and updates

The ordinary CI APK is debug-signed. For an owner-controlled production release, add these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then run **Signed production release**. Keep an offline backup of the same keystore. A production key cannot update an installed debug-signed APK in place; uninstalling the debug build also removes its private app history database, although already-published videos remain in Downloads unless separately deleted.

## Test scope

Automated emulator verification is not physical-device certification. Before broad distribution, test representative public links from Instagram, TikTok, Facebook, Pinterest, X, and Reddit on the actual phones and networks that will be used. Record failures honestly; do not treat a direct MP4 fixture as proof of social extraction.
