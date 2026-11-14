# TTDL - TikTok Video Downloader (M3E)


## Download APIs

The app uses multiple public APIs with automatic fallback. If one service is unavailable or fails, the next one is tried automatically.

| API | Method | Description |
|-----|--------|-------------|
| [tikwm.com](https://www.tikwm.com) | POST / GET | Primary source. Supports HD (no watermark) links |
| tikwm.com (mirror) | POST | Alternative endpoint of the same service |
| sl-bjs Worker | GET | Cloudflare Worker fallback |
