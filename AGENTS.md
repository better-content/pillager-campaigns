# AGENTS.md

## Commit discipline

- Treat existing dirty and untracked files as user-owned unless the user explicitly says otherwise.
- Keep each commit focused on one coherent change.
- Run `./gradlew verifyFull --no-daemon` before committing and pushing.
- Do not push while validation is failing.
