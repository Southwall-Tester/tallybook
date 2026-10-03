# Repository working agreement

- Build an Android personal budgeting app for students. Keep real ledger data and fictional demo data separate.
- Use Java, the existing native Android UI and Gradle wrapper. Money uses integer minor units; validate dates and amounts before saving.
- Keep data local. Never commit personal bills, screenshots with account data, credentials, signing keys, `.tools`, or generated build outputs.
- Deliver one coherent feature or fix at a time. Run relevant checks, inspect the diff, commit, then push to the configured GitHub remote; ongoing pushes are authorized by the user. Do not force-push or rewrite remote history.
- The early prototype may use `main` for small reviewed changes. Use `feature/<short-name>` and a pull request for larger changes or collaboration.
- Before a feature checkpoint, run `scripts/build.ps1`; for UI, storage or integration changes also run the project emulator checks as described in `docs/DEVELOPMENT.md`.
- Preserve previous APK versions. Increase `versionCode` and `versionName` for a new deliverable. Keep implemented, locally tested, CI-tested and real-WeChat-tested claims separate.
- Do not present the experimental WeChat hook as a public API or claim daily background synchronization without device evidence.
