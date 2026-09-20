# Extraction backend

Deploy this directory as a Docker web service behind HTTPS, port 8080. No account, cookies, passwords or client secret are needed. Set the GitHub Actions repository variable `BLACK_HOLE_API_URL` to the service's public HTTPS origin, then rebuild the app.

Example local server command: `docker build -t black-hole-api backend` then `docker run --rm -p 8080:8080 --memory=1g --cpus=2 --pids-limit=128 black-hole-api`.

Production requirements: HTTPS reverse proxy with per-IP rate limits, 4+ GB temporary disk, outbound firewall rejecting all private/link-local/metadata networks, and one application worker. Socket guards validate actual public destinations inside extraction; keep the firewall as defense in depth. Forwarded IPs are intentionally not trusted. If running behind a proxy, rate-limit at that proxy. Downloads and job records expire after 30 minutes; requests and source URLs are not access-logged. Random 128-bit job IDs act as temporary download capabilities. Do not enable debug logging of URLs.

The app polls extraction jobs, then downloads a finite MP4 from this server. Server-side FFmpeg merges separate H.264/AAC tracks; no native engine ships in the APK. Highest compatible H.264 MP4 is preferred. Private, age-restricted, DRM-protected, login-required, unsupported and anti-bot-blocked URLs fail explicitly. Platform support varies with source changes and server location. No promise of every video or removing all watermarks.

Only HTTP(S) formats or native HLS are accepted; arbitrary external downloaders are not used. Files are capped and worker processes have a five-minute deadline. Worker stderr is discarded to avoid logging source tokens. yt-dlp is kept within a maintained version range: after a tested deployment, freeze its exact installed version with `pip freeze` and review updates regularly. This backend has not been deployed by merely adding these files.
