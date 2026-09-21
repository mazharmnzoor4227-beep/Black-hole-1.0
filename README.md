> Delivery status: social downloads require the live backend deployment below. Diagnostic APKs are not final social-downloader releases. Version 1.0.2 keeps the existing UI, verifies both video and audio before saving, fixes Android 9 history deletion, deduplicates extraction jobs and improves backend media validation; it does not pretend an undeployed server is connected.

## Free personal deployment (2–3 people)

`render.yaml` deploys the existing extraction Docker service on Render's Free plan.
Sign in to Render, create a Blueprint from this repository, and check that the service plan says Free before deploying. No database or paid disk is needed. Do not add a payment method for this free-only setup. Render can suspend service at free limits and may reject high outbound traffic; this is not an unlimited or production-SLA service.

After deployment, put its actual HTTPS origin in `backend-url.txt` (public configuration, no secrets). `/health` must identify `service=black-hole` and `api_version=1`. Set repository Actions variable `BLACK_HOLE_TEST_URL` to a public social video for the extraction delivery check. CI downloads that actual video and checks the full MP4 response before accepting a configured build. Repeat with representative Instagram/TikTok/Facebook/Pinterest URLs before claiming platform coverage; one successful sample never proves all links work.

The server may take about a minute to wake after inactivity. The phone waits for health before creating a download job. Files are temporary on the server and permanent only after saving on the phone. The build keeps yt-dlp and FFmpeg on the server, not in the APK.

Video policy: highest available compatible H.264/AAC source format, resolution prioritized, with stream-copy audio/video merging. The backend and Android client both reject output without an audio track. No upscale or re-encoding. The platform's available stream can differ from the creator's original upload; original-upload quality and every public URL cannot be guaranteed. A higher-resolution incompatible codec can be excluded for older-phone playback compatibility.

# BLACK HOLE

A clean Kotlin Android project created from scratch. No old BLACK HOLE code, accounts, Firebase, ads, embedded Python, FFmpeg or native APK libraries.

## App

- Exact supplied black-hole image for the home action and launcher icon.
- Pure black edge-to-edge home; only the hole rotates during analysis/download/save.
- Foreground clipboard access and Android text sharing; tap the hole to begin.
- Coroutine foreground download service with cancellation from its notification.
- Actual byte progress, no invented resolution; validates the downloaded video before publishing.
- Android 9 storage permission; Android 10+ MediaStore pending-file publication.
- History with actual dimensions, size, source/date; open, share, confirmed deletion.
- Retry by tapping after an error. One active download at a time. No background clipboard scraping.

## Build and APK

JDK 17, Gradle 8.11.1, Android SDK 36 / build tools 36.0.0. Run `./gradlew :app:assembleDebug :app:lintDebug` (or `gradlew.bat` on Windows). Standard Gradle wrapper files are committed.

GitHub Actions uploads `BLACK-HOLE-diagnostic-APK` containing the installable debug-signed `BLACK-HOLE-1.0.2-diagnostic.apk`, exact byte size, SHA-256, commit SHA, signature and manifest evidence. Device jobs install that exact APK and launch the real MAIN activity on API 28, 29 and 36. They test a real generated MP4 over HTTP (emulator-only debug exception), save/readback, 100%, history and screen black pixels. Screenshots and instrumentation output are separate artifacts.

Minimum Android: 9 / API 28. Target/compile: Android 16 / API 36. No ABI filters or native `.so` files. Compatibility across every manufacturer or future Android release is not claimed by compilation alone.

A testing APK is not a production-signed release. For long-term distribution configure `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` securely in the build environment and run `gradle :app:assembleRelease`. The dedicated **Signed production release** workflow accepts repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` plus the backend URL variable and refuses an unconfigured production build. Preserve the same signing key for updates; changing a debug key can require uninstalling the previous test build.

## Social-platform extraction

Direct HTTPS MP4 links work without a backend. Social links require deployment of `backend/` and the Actions repository variable `BLACK_HOLE_API_URL=https://your-service.example`. Re-run the build after setting it. An empty endpoint displays a clear error; it does not simulate downloading. Backend code alone does not activate TikTok/Instagram/Facebook/Pinterest/X/Reddit downloads.

Public videos can still require login or be blocked by platforms, region, anti-bot checks or format restrictions. This app does not bypass those controls. Highest available compatible H.264/AAC MP4 is preferred server-side. No guaranteed 4K/8K or universal watermark removal.

## Save location

`Internal storage/Download/BLACK HOLE/` (the Downloads app may label it `Downloads/BLACK HOLE`). On Android 10+ files are published through MediaStore Downloads with `video/mp4`. Gallery visibility depends on the gallery's Downloads-folder indexing; Files/Downloads and history open/share remain available. Android 9 also requests media scanning.

## Phone acceptance tests

1. Download and extract `BLACK-HOLE-diagnostic-APK` from a green Actions run; install `BLACK-HOLE-1.0.2-diagnostic.apk`. Allow installation from your file manager if Android asks. Until a live backend passes the social check, this remains a direct-MP4 diagnostic build.
2. Launch from the launcher: black screen, supplied hole, small HISTORY; no login or URL box.
3. With no copied URL, tap: `COPY A VIDEO LINK FIRST`.
4. Copy or share a public direct MP4 URL. Tap the hole; allow storage on Android 9 and optionally notifications on Android 13+.
5. Confirm rotation and actual progress; then 100%, stopped rotation, `DOWNLOAD COMPLETE` and `SAVED TO DOWNLOADS`.
6. Open Files → Downloads → BLACK HOLE; play the complete video. Check Gallery if it indexes Downloads.
7. Open HISTORY; test play, share and confirmed delete.
8. Repeat on mobile data, Wi-Fi, offline, invalid URL and interrupted connection. Lock the phone during a longer transfer, reopen, check result. Some OEM battery restrictions may interrupt services.
9. After backend deployment/rebuild, test one public URL from each desired social source. Social end-to-end tests must be reported separately from the direct MP4 fixture test.

## Limits

One download at a time; no process-death resume; failed/cancelled partial files are cleaned on the next download. Client cap 2 GB; server extraction cap 1 GB and five minutes. Unknown Content-Length shows bytes until real completion rather than a made-up percentage. Temporary app storage is used for validation before publication, requiring up to twice the file size. History is local and is not restored after uninstall. The server requires hosting, bandwidth and maintenance; no hosted service is supplied by this repository alone.
