# Render Keep-Alive Health Check — Design Spec

Date: 2026-08-16

## Goal

The deployed backend (`https://flowmatic-backend-3c9q.onrender.com`) runs on Render's free tier,
which spins the instance down after ~15 minutes of inactivity. The next incoming request then pays
a cold-start penalty (~30-60s, confirmed via the Render "waking up" screen) before it's served. Add
a scheduled job that pings the service every 14 minutes — just under Render's 15-minute idle
threshold — so it never goes to sleep.

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
    - cron: "*/14 * * * *"
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
curl -sS -o /dev/null -w "%{http_code}" --max-time 30 --retry 1 --retry-delay 5 \
  https://flowmatic-backend-3c9q.onrender.com/
```

- `--max-time 30`: a cold-started instance can take up to ~30-60s to respond per the observed
  Render startup sequence; 30s covers the common case without hanging the job indefinitely.
- `--retry 1 --retry-delay 5`: one retry after a 5s pause, so a single slow/cold response doesn't
  flip the whole job red — a real outage still fails after the retry is exhausted.
- A non-200 response (or curl exit failure) fails the step, which shows as a red X in the Actions
  tab. That's the alerting mechanism — no additional notification integration.

## Free-tier limits (explicitly called out, not just implied)

- **GitHub Actions**: free/unlimited for public repos — no minute budget to track here.
- **Render free tier**: 750 instance-hours/month shared across *all* free-tier services in the
  Render account. Pinging this one service every 14 minutes keeps it up ~24/7, consuming roughly
  720-730 of those 750 hours by itself. Decision (confirmed with user): accept this trade-off,
  since this is currently the only free-tier Render service. Adding another free-tier Render
  service later would exceed the monthly cap and needs revisiting this workflow (e.g. adding an
  active-hours window) at that time.
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
