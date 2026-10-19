# Where the project skills come from

Only instruction files (SKILL.md, reference .md, HTML templates) were copied. No scripts, installers or
binaries. Each file was read before it was added.

| Skill | Source | Licence | Use in MANHWA |
|---|---|---|---|
| ponytail | github.com/DietrichGebert/ponytail | MIT | Smallest complete change on any coding task |
| ponytail-review | github.com/DietrichGebert/ponytail | MIT | Review a change before pushing (bugs, risk, load, tests) |
| ponytail-audit | github.com/DietrichGebert/ponytail | MIT | Whole-repo audit report |
| changelog-generator | github.com/ComposioHQ/awesome-claude-skills | see source | "What's new" text and release notes from git commits |
| diagram-design | github.com/cathrynlavery/diagram-design | MIT | Architecture and flow diagrams for the docs (scripts left out) |

| kotlin-patterns | github.com/affaan-m/everything-claude-code (ECC, `76aaa77`) | MIT | Idiomatic Kotlin |
| kotlin-coroutines-flows | ECC | MIT | Coroutines, Flow and StateFlow on Android |
| kotlin-testing | ECC | MIT | Kotest and MockK tests |
| android-clean-architecture | ECC | MIT | Module layers and data flow |
| compose-multiplatform-patterns | ECC | MIT | Compose state, recomposition and performance |

| emil-design-eng | github.com/emilkowalski/skills (`2d22863`) | MIT | UI polish and the small details (licence in its folder) |
| animate, review-animations, improve-animations, find-animation-opportunities, animation-performance, animation-vocabulary | emilkowalski/skills | MIT | Motion: whether to animate, curves, durations, interruption, performance (written for the web; the principles carry to Compose) |
| apple-design | emilkowalski/skills | MIT | Springs, gestures, sheets, depth and typography in the Apple style |
| break-ui | emilkowalski/skills | MIT | Worst-case data (long names, Arabic text, empty states) to find UI that breaks |
| mobile-native, animate-expo, ask-sonner, pick-ui-library, prototype, write-swift | emilkowalski/skills | MIT | Web, React Native, Swift; added as asked, of little use in a Kotlin app |

| planning-with-files | github.com/OthmanAdi/planning-with-files (`d363544`) | MIT | Plan long tasks in task_plan.md / findings.md / progress.md (hooks and scripts left out) |
ECC agents in `.claude/agents/` (MIT, same version): `kotlin-reviewer` (report-only review of Kotlin changes),
`kotlin-build-resolver` (smallest fix for a failing build), `silent-failure-hunter` (swallowed errors and bad
fallbacks). ECC's Kotlin rules are in `docs/ecc-kotlin-rules/` with its licence.

From ECC, not added: its hooks (they run code on every action), its MCP configs, the other 300 skills and 68
agents (other languages and platforms), `verification-loop` and `performance-optimizer` (written for npm and
web pages, not Gradle and Android), `foundation-models-on-device` (Apple only).

Looked at and not added:

| Skill | Why not |
|---|---|
| Scrapling | Built to get past anti-bot protection (Cloudflare Turnstile); against the project rule of never bypassing CAPTCHA or site protections |
| REA (reverse-engineer-anything) | Needs its own MCP server and engines downloaded with npx; not needed for an open-source app |
| last30days | Needs Python scripts and several paid API keys |
| impeccable, ui-ux-pro-max, taste-skill, hallmark | Made for websites (HTML/CSS/React) and lean on their own scripts; the app is Kotlin + Jetpack Compose |
| humanizer | Rewrites English prose; the app's translations are Arabic |
