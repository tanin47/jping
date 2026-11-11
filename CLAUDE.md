# CLAUDE.md

- We don't need to run Chrome to verify the UI.
- Don't add code comments unless they explain complex logic.
- When running tests, set the env `HEADLESS=true` (e.g. `HEADLESS=true ./gradlew test`).
- Never use the local `main` branch. Always use `origin/main` (e.g. `git fetch origin` then branch from or diff against `origin/main`).
