# Contributing to light-query

Thanks for your interest! light-query is a contract-driven project: the
design document is the source of truth.

## Ground rules

1. **DESIGN.md first.** Behavioural changes start as a PR against
   `docs/DESIGN.md` (the contract), then a second PR implements it. Any code
   that deviates from the document is treated as a bug.
2. **Every feature ships with tests.** SQL generation changes need snapshot
   assertions for MySQL *and* PostgreSQL (`SqlSnapshotTest`); behaviour
   changes need H2 integration tests. See the test matrix (T1–T10) in
   DESIGN.md §11.
3. **Public API needs JavaDoc**, and messages of every framework exception
   must state the problem *and* how to fix it.
4. **No new runtime dependencies.** The only compile dependency is
   `jakarta.persistence-api`. Test deps are fine.
5. Source files are Apache-2.0 licensed; keep the project LICENSE intact.

## Local development

```bash
# JDK 21 required
mvn verify          # full build + tests
mvn test            # tests only
```

## Pull requests

- One logical change per PR; rebase before merging.
- CI must be green (build, tests).
- Update CHANGELOG.md under **Unreleased** for user-visible changes.
