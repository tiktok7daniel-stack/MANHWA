# MANHWA: rules for Claude Code

Android app (Kotlin, Jetpack Compose, Voyager, Metro DI) that reads manhwa and translates them into Arabic.
Full history and file map: `docs/SESSION_MEMORY.md`, `docs/MANHWA_HANDOFF.md`.

- Reply to the user in Egyptian Arabic, short and plain.
- No build after each request: commit with `[skip ci]` and list the change in `docs/PENDING_CHANGES.md`. Build only
  when the user says "اعمل بيلد نهائي" (workflow_dispatch of `build_branch.yml`), then fast-forward `main` and send
  the arm64 artifact link.
- Icons: Material Symbols only (`mihon.icons.materialsymbols.*`), no emoji in the app. UI in ManhwaKit
  (`glass()`, NeonButton, GhostButton, TopBar, GlowIcon).
- Never delete a feature or the user's data; new stored fields get backward-compatible defaults.
- Never use published or stolen API keys, fake accounts, or anything that gets past CAPTCHA or site protections.
- Never claim a build, merge or APK before checking it on GitHub.
- Strings: `i18n-at/src/commonMain/moko-resources/{base,ar}/strings.xml`, used as `ATMR.strings.<key>`.
- Tests: `app/src/test/java/eu/kanade/manhwa/*Test.kt` (JUnit 5 + kotest); no `:` in test names.
- Kotlin style and review: `docs/ecc-kotlin-rules/`, the `kotlin-*` skills, and the `kotlin-reviewer` agent before a
  final build; `kotlin-build-resolver` when a build fails.
