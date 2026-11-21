## Summary

Opt-in **auto download**: every new video from your subscriptions is downloaded in the background at your chosen quality caps.

**Settings → Audio and video → Automatic downloads:**

```
Automatic downloads
├── Auto download videos           [Switch, default off]
├── Only download on Wi-Fi         [Switch, default on]
├── Auto download video quality    [Auto / 2160p … 144p, default 1080p]
└── Auto download audio quality    [Auto / 196 … 32 kbps, default 128 kbps]
```

The pickers and the Wi-Fi switch are greyed out while the feature is off. All defaults are conservative: off out of the box, Wi-Fi-only, 1080p/128kbps caps — `Auto` is an explicit opt-in for "best available".

**Control flow** — three triggers feed one sweep, all reuse the existing `DownloadService` pipeline:

```
app start ──────────────► AutoDownloadScheduler.enqueuePeriodic (every 6h)
toggle switched on ─────► AutoDownloadScheduler.runNow
subscriptions tab shown ─► SubscriptionsFragment feed sweep

                          └─► AutoDownloadWorker / feed sweep
                               ├─ SubscriptionHelper.getFeed(FeedRefresh.All)
                               ├─ skip shorts, live & upcoming streams
                               ├─ skip already downloaded / downloading videos
                               └─ DownloadHelper.autoDownloadFeedItems
                                    └─ per video: enqueueAutoDownload(videoId)
                                         ├─ respects Wi-Fi-only + external downloader
                                         ├─ picks best video/audio stream ≤ cap
                                         │   └─ audio prefers main track over
                                         │      dubbed/descriptive (like the dialog)
                                         └─ DownloadService.start(...)
```

Implementation notes:
- The worker mirrors the existing `NotificationWorker` pattern (`WorkManager`, `FeedRefresh.All`, retry on failure) and is scheduled in `LibreTubeApp.onCreate` with `KEEP`, so no duplicate schedules.
- Downloads are tracked by the `download` table (`exists()`), so failures are naturally retried on the next check instead of being enqueued twice.
- Failed enqueues are skipped quietly — no crash, no duplicate; the in-app sweep runs whenever the subscriptions tab is opened, which is the primary trigger on Android 12+ where background foreground-service starts are restricted.
- Quality picking reuses the existing cap logic (`getWhileDigit`, lowest-stream fallback) and honours an external download provider preference like all manual paths.

## Evidence

- `./gradlew :app:assembleDebug` passes — the CI gate (`.github/workflows/ci.yml`). Verified on the same JDK (17, temurin) as CI.
- Emulator (debug build): the new category renders with the updated summary; toggling the feature on immediately enqueues `AutoDownloadWorker` which completes `SUCCESS`; the worker also runs (and short-circuits) on app start when the feature is off.
- No changes to manual downloads: same service, same DB rows, same notifications.

## Merge Danger

**Door:** two-way — opt-in, off by default, trivially revertible.

**Blast Radius:** storage and data use — but only for users who enable it, bounded by the Wi-Fi-only default and the 1080p/128kbps caps. On first enable the whole *current* feed (not the archive) is swept, so a heavy subscriber with a long feed may see a burst of downloads — the caps and Wi-Fi gate keep that bounded. Draft because the feed length, shorts handling, and check frequency deserve maintainer input before this is considered for merge.