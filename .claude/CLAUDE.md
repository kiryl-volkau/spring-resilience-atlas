# CLAUDE.md

This repository is a Claude Code plugin, `resilience-audit`, that audits large Spring Boot
applications for reliability failure modes. `README.md` describes the architecture and
roadmap. The full design rationale is `sources/deep-research-report(10).md` (~2,250 lines):
grep it for the section you need rather than reading it whole. `sources/` holds research notes,
spikes and reviews; it is git-ignored and exists only on the author's machine.

## Plugin conventions

- The plugin is `plugin/`, and only that folder ships: manifest in
  `plugin/.claude-plugin/plugin.json`, skills in `plugin/skills/<name>/SKILL.md`, subagents in
  `plugin/agents/`, hooks in `plugin/hooks/hooks.json`, and the `claude plugin eval` cases in
  `plugin/evals/`. Use `skills/`, not the legacy `commands/`.
- The repository root holds what users never install: the marketplace manifest
  (`.claude-plugin/marketplace.json`, source `./plugin`), the development build (`pom.xml`),
  the golden fixture (`fixtures/`) and the scanner's own tests and tools (`evals/`). Keep
  development material out of `plugin/`.
- Keep `SKILL.md` under ~500 lines. Rule catalogues, examples and schemas go into
  supporting files in the skill directory, which load only when referenced.
- Reference plugin files through `${CLAUDE_PLUGIN_ROOT}`. At runtime the working directory
  is the audited Spring repository, not this one.
- Scripts must run on Windows, macOS and Linux.
- The plugin targets Anthropic's directory, which holds for manual review any file over
  256 KiB (images excepted), any binary other than an image or font, and more than 512 files.
- Test against a real Spring repository with `claude --plugin-dir <this repo>/plugin`; skills are
  invoked as `/resilience-audit:<skill>`, and `/reload-plugins` picks up edits.
- The evaluation corpus and its rules are in `evals/corpus.md`, which is git-ignored because
  the repository is public. Never run the scanner in a corpus project's live working tree,
  only in a snapshot at the pinned commit. Never copy corpus sources or results into this
  repository, and never name corpus projects in committed files.

## Scanner

- `plugin/scanner/` has **no dependencies**: parsing uses the JDK's javac Tree API, and JSON is
  hand-rolled. Do not add libraries. Compile with `--release 17`.
- The plugin ships sources, never the jar. Skills run `scanner/Launch.java`, which compiles
  `src/main/java` plus the resources the pom bundles (`src/main/resources`, `schemas/`,
  `references/`) on first use. Keep the two builds' inputs in step, and keep `Launch.java` a
  single source file that runs on Java 17.
- Build the development jar with `mvn -o -q package` (into `dist/`) for the golden test. The pinned plugin versions resolve from
  the local cache, because the configured Maven mirror is often unreachable.
- `plugin/schemas/entrypoint-rules.md` is the contract for every entry-point record. Change the
  rules, the scanner and `fixtures/spring-golden/expected/` together. Then run the golden
  test (`evals/README.md`); it must report 0 differences.
- Effect signals (`effects/`) and code-hint rules (`report/Risk.java`) are pinned by
  `expected/signals.tsv` and `expected/risk.tsv`. A rule change that moves a level updates them
  in the same change.
- When trying the scanner on a real project, pass `--atlas <scratch dir>` so nothing is
  written into its working tree.

## Design rules every skill follows

- Scripts do mechanical discovery and write the atlas. Claude queries the atlas and opens
  only the files needed for one hypothesis; a skill never greps the whole target
  repository for something the atlas already holds.
- Each audit skill states a source-reading budget and the conditions for expanding it.
- Atlas records and findings are tied to the target's commit SHA and to file + symbol
  evidence, with discovery status `STATIC`, `RUNTIME_CONFIRMED`, `INFERRED` or `UNKNOWN`.
- Findings keep severity and confidence as separate fields and list existing mitigations
  before recommending a fix.
- "No findings" is never reported as "safe" unless the coverage ledger shows what ran.
- Audit skills never modify the audited application's code.
