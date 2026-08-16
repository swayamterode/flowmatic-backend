# Render Keep-Alive Health Check — Design Spec

Date: 2026-08-16

## Goal

The deployed backend (`https://flowmatic-backend-3c9q.onrender.com`) runs on Render's free tier,
which spins the instance down after ~15 minutes of inactivity. The next incoming request then pays
a cold-start penalty (~30-60s, confirmed via the Render "waking up" screen) before it's served. Add
a scheduled job that pings the service every 10 minutes — leaving 5 minutes of margin under
Render's 15-minute idle threshold, to absorb GitHub's best-effort scheduling delays — so it never
goes to sleep.

## Approach

A GitHub Actions scheduled workflow, `.github/workflows/keep-alive.yml`. Chosen over an external
uptime monitor (UptimeRobot, cron-job.org) because:

- The repo (`swayamterode/flowmatic-backend`) is public, so GitHub Actions minutes are unlimited —
  free regardless of run frequency.
- It's version-controlled code living in the repo, matching what was asked for, rather than
  third-party dashboard config outside git's visibility.
- Run history/failures are visible directly in the repo's Actions tab.

## Trigger

```yaml
on:
  schedule:
    - cron: "*/10 * * * *"
  workflow_dispatch: {}
```

`workflow_dispatch` is included so the job can be run on demand (used to verify it works after
creation, and for manual checks later). Cron schedule is UTC and runs 24/7 — no active-hours
window, per explicit decision below.

## What the job does

One step: curl the existing `GET /` endpoint (`WelcomeController`, already returns
`{"status":"UP", "timestamp": ...}` — no application code changes needed) and fail the step if the
HTTP status isn't 200.

```bash
status=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 90 --retry 2 --retry-delay 15 --retry-all-errors \
  https://flowmatic-backend-3c9q.onrender.com/ || true)
```

- `--max-time 90`: a cold-started instance can take up to ~30-60s to respond per the observed
  Render startup sequence. The original `--max-time 30` proved too tight — a live cold-start run
  timed out under it — so this was widened to 90s (commit `4fd3c6d`) to comfortably cover a cold
  start plus overhead.
- `--retry 2 --retry-delay 15`: up to two retries with a 15s pause between them, so a slow/cold
  response doesn't flip the whole job red on its own — a real outage still fails once the retry
  budget is exhausted.
- `--retry-all-errors`: without this flag curl's `--retry` only covers a subset of transient
  failures by default (e.g. HTTP 5xx/408/429); this extends the retry budget to connection-refused,
  TLS, and other connect-level failures too.
- `|| true` on the failure path: the workflow's `run:` step executes under `bash`'s default
  `errexit` (`set -e`). Without `|| true`, a nonzero curl exit (the endpoint being fully
  unreachable, for instance) would abort the script right at the `status=$(...)` assignment, before
  the script's own friendly "Health check failed" message ever gets a chance to print. `|| true`
  lets the script continue so the explicit status check below can report the failure clearly and
  exit non-zero on its own terms. This doesn't lose the failure signal: curl's `-w "%{http_code}"`
  already writes `000` itself when the request never receives an HTTP response, so `$status` still
  ends up `000` on that path — no separate placeholder needs to be injected (an earlier `|| echo
  000` did inject one, which double-printed as `000000` in the logs; fixed by switching to `||
  true`).
- A non-200 response (or curl exit failure, surfaced as status `000`) fails the step, which shows
  as a red X in the Actions tab. That's the alerting mechanism — no additional notification
  integration.

## Free-tier limits (explicitly called out, not just implied)

- **GitHub Actions**: free/unlimited for public repos — no minute budget to track here.
- **Render free tier**: 750 instance-hours/month shared across *all* free-tier services in the
  Render account. Pinging this one service every 10 minutes keeps it up ~24/7, consuming roughly
  744 of those 750 hours by itself (24 hours/day × ~31 days), leaving ~6 hours of headroom.
  Decision (confirmed with user): accept this trade-off, since this is currently the only
  free-tier Render service. Adding another free-tier Render service later would exceed the
  monthly cap and needs revisiting this workflow (e.g. adding an active-hours window) at that
  time.
- **GitHub scheduled workflow auto-disable**: GitHub automatically disables `schedule`-triggered
  workflows after 60 days with zero commits to the repository. If the repo goes quiet for that
  long, the keep-alive silently stops firing (no error, no notification) until someone commits or
  manually re-enables the workflow in the Actions tab. Not solved by this spec — noted as an
  operational gotcha.

## Testing

After the workflow file is created and pushed, trigger it manually via
`gh workflow run keep-alive.yml` and check the run's status/log via `gh run list` /
`gh run view --log` to confirm it actually reaches the live URL and reports success — not just that
the YAML is syntactically valid.

## Explicitly out of scope

- Active-hours windowing (e.g. only ping 8am-11pm) — rejected; user chose 24/7 given this is the
  only free-tier service on the Render account.
- A dedicated `/health` or `/actuator/health` endpoint — the existing `GET /` already reports
  `status: UP`; adding Spring Boot Actuator or a new endpoint isn't needed for this.
- Alerting beyond the Actions tab's built-in red-X-on-failure (e.g. Slack/email notification on
  ping failure) — not requested.
