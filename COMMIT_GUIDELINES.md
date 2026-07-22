# Commit Guidelines

Run from the project root. Do the checklist, then write a good message.

## Before you commit

```bash
git diff --cached --name-only   # 1. check nothing secret is staged
./mvnw spotless:apply           # 2. format the code
./mvnw clean verify             # 3. build + tests must pass
git add <files>                 # 4. stage only what belongs
```

- Never stage `application.properties` or any real secret/password/key.
- Don't commit if step 3 fails.

## Writing the message

Use Conventional Commits: `type(scope): short summary` (imperative, ≤72 chars).

| Type | For |
|------|-----|
| `feat` | new feature |
| `fix` | bug fix |
| `refactor` | code change, no behavior change |
| `style` | formatting only (e.g. Spotless) |
| `test` | tests |
| `docs` | documentation |
| `chore` | build / deps / config |

Examples:
```bash
git commit -m "feat(auth): add email OTP verification"
git commit -m "fix(jwt): reject wrong token type"
git commit -m "chore: add spotless plugin"
```

## Push

Work on a branch and open a PR — never push straight to `main`.
```bash
git checkout -b feat/<name>
git push -u origin feat/<name>
```

> Tip: `./scripts/precommit.sh` runs the checks above for you.
