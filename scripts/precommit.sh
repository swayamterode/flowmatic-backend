#!/usr/bin/env bash
#
# precommit.sh — automates the pre-commit checklist from COMMIT_GUIDELINES.md
#
# Steps:
#   1. Secret scan   — fail if secrets or application.properties are staged
#   2. Format        — check (or --fix) Java formatting with Spotless
#   3. Build + test  — ./mvnw clean verify
#
# Usage:
#   ./scripts/precommit.sh            # check formatting, do NOT modify files
#   ./scripts/precommit.sh --fix      # auto-format (spotless:apply) before checking
#   ./scripts/precommit.sh --no-build # skip the slow build+test step
#   ./scripts/precommit.sh --install-hook   # install as .git/hooks/pre-commit
#
# Exit code 0 = all checks passed, non-zero = something failed.

set -uo pipefail

# ---- resolve project root (works from anywhere) ----
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "Not inside a git repository." >&2
  exit 1
}
cd "$ROOT"

MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"   # fall back to system maven if wrapper missing

# ---- pretty output ----
if [ -t 1 ]; then
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; BLU=$'\033[34m'; BLD=$'\033[1m'; RST=$'\033[0m'
else
  RED=; GRN=; YEL=; BLU=; BLD=; RST=
fi
step() { echo; echo "${BLU}${BLD}==> $*${RST}"; }
ok()   { echo "${GRN}  ✓ $*${RST}"; }
warn() { echo "${YEL}  ! $*${RST}"; }
fail() { echo "${RED}  ✗ $*${RST}"; }

# ---- flags ----
FIX=0; DO_BUILD=1
for arg in "$@"; do
  case "$arg" in
    --fix)         FIX=1 ;;
    --no-build)    DO_BUILD=0 ;;
    --install-hook)
      HOOK="$ROOT/.git/hooks/pre-commit"
      printf '#!/usr/bin/env bash\nexec "%s/scripts/precommit.sh" --no-build\n' "$ROOT" > "$HOOK"
      chmod +x "$HOOK"
      echo "${GRN}Installed pre-commit hook -> $HOOK${RST}"
      echo "(runs secret scan + format check on every commit; skips the slow build)"
      exit 0 ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) fail "unknown option: $arg"; exit 2 ;;
  esac
done

FAILED=0

# ============================================================
# STEP 1 — Secret scan
# ============================================================
step "Step 1/3  Secret scan"

# What is staged? (Added/Copied/Modified)
STAGED="$(git diff --cached --name-only --diff-filter=ACM)"

if [ -z "$STAGED" ]; then
  warn "Nothing staged — run 'git add <files>' first. Scanning skipped."
else
  # 1a. application.properties must never be staged
  if echo "$STAGED" | grep -q 'src/main/resources/application.properties'; then
    fail "application.properties is staged — it contains real secrets and is git-ignored."
    fail "Unstage it: git restore --staged src/main/resources/application.properties"
    FAILED=1
  fi

  # 1b. scan the ADDED lines of the staged diff for secret-looking values
  #     (ignore the *-example.properties template, which holds placeholders)
  ADDED="$(git diff --cached -U0 --diff-filter=ACM -- . ':(exclude)*example*' \
            | grep -E '^\+' | grep -vE '^\+\+\+' || true)"

  # Broadly match secret-looking assignments, then drop two false-positive shapes:
  #   - empty quoted values, e.g. MVNW_PASSWORD=''  (the vendored mvnw wrapper)
  #   - code expressions, e.g. accessToken = jwtUtil.generateAccessToken(email)
  #     (an identifier / member access followed by a call — not a hardcoded literal)
  SECRET_HITS="$(echo "$ADDED" | grep -inE \
      'password[[:space:]]*=[[:space:]]*[^[:space:]$]|(secret|api[_-]?key|access[_-]?token|private[_-]?key)[[:space:]]*[:=][[:space:]]*["'\''0-9A-Za-z/+]|BEGIN[[:space:]]+[A-Z ]*PRIVATE KEY' \
      | grep -vE '[:=][[:space:]]*['\''"]['\''"]' \
      | grep -vE '[:=][[:space:]]*[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*\(' \
      || true)"

  if [ -n "$SECRET_HITS" ]; then
    fail "Possible secret(s) in staged changes:"
    echo "$SECRET_HITS" | sed 's/^/      /'
    fail "Remove the secret, use an env var / placeholder, then re-stage."
    FAILED=1
  fi

  [ "$FAILED" -eq 0 ] && ok "No secrets or ignored config staged."
fi

# ============================================================
# STEP 2 — Formatting (Spotless)
# ============================================================
step "Step 2/3  Formatting (Spotless / Google Java Format)"

if [ "$FIX" -eq 1 ]; then
  echo "  running spotless:apply ..."
  if "$MVNW" -q spotless:apply; then
    ok "Formatted. Re-stage any changed files: git add -u"
  else
    fail "spotless:apply failed."
    FAILED=1
  fi
else
  echo "  running spotless:check ..."
  if "$MVNW" -q spotless:check; then
    ok "All files correctly formatted."
  else
    fail "Formatting violations found. Fix with: ./scripts/precommit.sh --fix"
    fail "                             (or:  $MVNW spotless:apply )"
    FAILED=1
  fi
fi

# ============================================================
# STEP 3 — Build + test
# ============================================================
if [ "$DO_BUILD" -eq 1 ]; then
  step "Step 3/3  Build + test (clean verify)"
  echo "  running $MVNW clean verify ... (this can take a while)"
  if "$MVNW" -q clean verify; then
    ok "Build + tests passed."
  else
    fail "Build or tests failed. Fix before committing."
    FAILED=1
  fi
else
  step "Step 3/3  Build + test — SKIPPED (--no-build)"
fi

# ============================================================
# Summary
# ============================================================
echo
if [ "$FAILED" -eq 0 ]; then
  echo "${GRN}${BLD}All checks passed — safe to commit.${RST}"
  echo "Next: git commit -m \"feat(auth): ...\"   (use Conventional Commits)"
  exit 0
else
  echo "${RED}${BLD}Checks FAILED — do not commit until resolved.${RST}"
  exit 1
fi
