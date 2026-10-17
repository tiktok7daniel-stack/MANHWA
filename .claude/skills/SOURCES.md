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

Looked at and not added:

| Skill | Why not |
|---|---|
| Scrapling | Built to get past anti-bot protection (Cloudflare Turnstile); against the project rule of never bypassing CAPTCHA or site protections |
| REA (reverse-engineer-anything) | Needs its own MCP server and engines downloaded with npx; not needed for an open-source app |
| last30days | Needs Python scripts and several paid API keys |
| impeccable, ui-ux-pro-max, taste-skill, hallmark | Made for websites (HTML/CSS/React) and lean on their own scripts; the app is Kotlin + Jetpack Compose |
| humanizer | Rewrites English prose; the app's translations are Arabic |
