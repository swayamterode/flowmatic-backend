# Render Keep-Alive Health Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions scheduled workflow that pings `https://flowmatic-backend-3c9q.onrender.com/` every 14 minutes so Render's free tier never spins the instance down from inactivity.

**Architecture:** A single workflow file, `.github/workflows/keep-alive.yml`, triggered by both `schedule` (cron) and `workflow_dispatch` (manual). One job, one step: curl the existing `GET /` endpoint and fail the step on a non-200 response. No application code changes.

**Tech Stack:** GitHub Actions (`ubuntu-latest` runner), `curl`, YAML.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-16-render-keep-alive-design.md`.
- Cron: `*/14 * * * *` (every 14 minutes, UTC, 24/7 — no active-hours window; confirmed with user).
- Target URL: `https://flowmatic-backend-3c9q.onrender.com/` (existing `WelcomeController`'s `GET /`, returns `{"status":"UP", ...}`).
- curl flags: `--max-time 30 --retry 1 --retry-delay 5` (cold start can take up to ~30-60s; one retry before failing).
- Note: the original `--max-time 30 --retry 1 --retry-delay 5` values above were superseded by a later fix based on live evidence (a cold-start run timed out under them) — widened in commit `4fd3c6d`, and further tuned in a subsequent review-fixes task. See the design spec's "What the job does" section for the current values.
- Non-200 response (or curl failure) must fail the job (`exit 1`) — that's the alerting mechanism.
- **This workflow must end up on `main`.** GitHub only triggers `schedule` events, and only lists a workflow for `workflow_dispatch`, from the repository's default branch (`main` here). A copy on a feature branch will never fire on its own.
- **Do not build this on the currently checked-out branch** (`feature/dashboard-executions-by-status`) — it has unrelated uncommitted changes (`pom.xml`, several test files, a new `ResendEmailService.java`/`ResendEmailServiceTest.java`) that must not be touched or carried into this work. Use a fresh branch off `main` (isolated worktree if available via `superpowers:using-git-worktrees`) named `chore/render-keep-alive`.
- Pushing the branch, opening the PR, and merging to `main` are actions visible to others / affecting shared state — **stop and get explicit user confirmation before each of those three actions**, regardless of what the plan says to do next.

---

### Task 1: Create and locally validate the keep-alive workflow file

**Files:**
- Create: `.github/workflows/keep-alive.yml`

**Interfaces:**
- Consumes: nothing (no dependency on other tasks).
- Produces: the workflow file itself, consumed by Task 2 (which pushes/merges it and triggers it live).

- [ ] **Step 1: Set up an isolated branch off `main`**

Use the `superpowers:using-git-worktrees` skill to get an isolated workspace. If a native worktree tool is available, use it. Otherwise, fall back to:

```bash
git fetch origin main
git worktree add .worktrees/chore-render-keep-alive origin/main -b chore/render-keep-alive
cd .worktrees/chore-render-keep-alive
```

This must be a separate working directory/branch from the current `feature/dashboard-executions-by-status` checkout — do not touch that checkout's uncommitted files.

- [ ] **Step 2: Bring the design spec and plan docs along**

The spec and plan docs (and any later fixups to the plan doc itself) were already committed to
`feature/dashboard-executions-by-status` under the `docs/superpowers/specs/` and
`docs/superpowers/plans/` paths, before any `.github/workflows/` commit exists. Find and cherry-pick
them in order, oldest first, so the PR is self-contained:

```bash
git log --reverse --format=%H origin/main..feature/dashboard-executions-by-status -- \
  docs/superpowers/specs/2026-08-16-render-keep-alive-design.md \
  docs/superpowers/plans/2026-08-16-render-keep-alive.md \
  | xargs -n1 git cherry-pick
```

If a cherry-pick reports "nothing to commit" (file already present via the `main` merge-base), run
`git cherry-pick --skip` and continue with the next.

- [ ] **Step 3: Write the workflow file**

Create `.github/workflows/keep-alive.yml` with exactly this content:

```yaml
# Keeps the Render free-tier instance from sleeping (spins down after ~15 min
# idle) by pinging it every 14 minutes, 24/7. See
# docs/superpowers/specs/2026-08-16-render-keep-alive-design.md for the
# free-tier trade-offs this accepts (Render's 750 free instance-hours/month,
# GitHub's 60-day auto-disable for inactive repos).
name: Render Keep-Alive

on:
  schedule:
    - cron: "*/14 * * * *"
  workflow_dispatch: {}

jobs:
  ping:
    runs-on: ubuntu-latest
    steps:
      - name: Ping health endpoint
        run: |
          status=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 30 --retry 1 --retry-delay 5 \
            https://flowmatic-backend-3c9q.onrender.com/)
          echo "Response status: $status"
          if [ "$status" != "200" ]; then
            echo "Health check failed with status $status"
            exit 1
          fi
```

- [ ] **Step 4: Validate the YAML parses correctly**

Run:

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/keep-alive.yml')); print('OK')"
```

Expected: `OK` printed, no exception.

- [ ] **Step 5: Dry-run the curl logic locally**

Run the exact command from the workflow step directly in the shell to confirm it reaches the real URL and reports 200 before trusting it inside CI:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" --max-time 30 --retry 1 --retry-delay 5 \
  https://flowmatic-backend-3c9q.onrender.com/
```

Expected: prints `200` (may take up to ~30-60s if the instance is currently asleep).

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/keep-alive.yml
git commit -m "feat(ops): add Render keep-alive health check workflow"
```

---

### Task 2: Ship it and verify it works live

**Files:**
- None new — pushes/merges the branch from Task 1.

**Interfaces:**
- Consumes: the committed `.github/workflows/keep-alive.yml` from Task 1.
- Produces: a running, verified scheduled workflow on `main`.

- [ ] **Step 1: CHECKPOINT — confirm with user before pushing**

Stop and ask the user for explicit confirmation before pushing the branch. Do not proceed on assumed consent.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin chore/render-keep-alive
```

- [ ] **Step 3: CHECKPOINT — confirm with user before opening the PR**

Stop and ask the user for explicit confirmation before opening the PR.

- [ ] **Step 4: Open the PR**

```bash
gh pr create --title "feat(ops): add Render keep-alive health check workflow" --body "$(cat <<'EOF'
## Summary
- Adds .github/workflows/keep-alive.yml, pinging https://flowmatic-backend-3c9q.onrender.com/ every 14 minutes so Render's free tier never spins the instance down.
- Design spec: docs/superpowers/specs/2026-08-16-render-keep-alive-design.md

## Test plan
- [ ] YAML validated locally with `python3 -c "import yaml; yaml.safe_load(...)"`
- [ ] After merge to main, manually triggered via `gh workflow run keep-alive.yml --ref main` and confirmed a successful run in the Actions log
EOF
)"
```

- [ ] **Step 5: CHECKPOINT — confirm with user before merging to `main`**

Stop and ask the user for explicit confirmation before merging. This step affects the shared default branch.

- [ ] **Step 6: Merge the PR**

Only after user confirmation:

```bash
gh pr merge --merge
```

(Use whichever merge method — merge/squash/rebase — matches this repo's usual convention; check recent PRs with `gh pr list --state merged --limit 5` if unsure.)

- [ ] **Step 7: Manually trigger the workflow on `main` to verify it works live**

```bash
gh workflow run keep-alive.yml --ref main
```

- [ ] **Step 8: Find the run and check its result**

```bash
sleep 10
gh run list --workflow=keep-alive.yml --limit 1
```

Take the run ID from the output, then:

```bash
gh run view <run-id> --log
```

Expected: the "Ping health endpoint" step logs `Response status: 200` and the run's overall conclusion is `success`.

- [ ] **Step 9: Report back to the user**

Confirm: workflow merged to `main`, manual run succeeded with a live 200 response, and the cron (`*/14 * * * *`) is now active and will fire automatically going forward.
