# Contributing

## Branches and pull requests

Work happens on a branch and lands through a pull request. `main` is the released line:
merging to it publishes to Maven Central, so nothing should arrive there unreviewed.

```bash
git checkout -b feat/short-description
# ... work ...
git push -u origin feat/short-description
gh pr create --fill
```

`.github/workflows/ci.yml` runs on every pull request: it builds both modules against a
real Redis, checks the published POM is still complete, and posts a per-suite test table to
the run summary. Merging with a red CI run publishes a broken release that cannot be
withdrawn, so let it finish.

## What merging does

Merging to `main` publishes a **permanent** release. The workflow works out the version
from the newest `v*` tag, builds, signs, uploads to the Central Portal, waits for Central
to confirm, tags `v<version>` and creates the GitHub Release. Nothing is committed back to
`main`.

Maven Central versions are immutable: they cannot be deleted, replaced or reused. A merge
burns a patch number forever.

### Merging without publishing

Put `[skip release]` in the **commit message**, not only in the pull request title. A squash
merge of a single-commit branch takes its subject from that commit and appends `(#N)`, so a
marker that lives only in the title never reaches the message the release workflow reads:

```
Update GitHub Actions to Node 24 runtimes [skip release]
```

That distinction is not academic. 0.2.4 published from a README-only change because #6 was
titled `[skip release]` while the commit it produced, `8bcdd89`, was not. #3 skipped
correctly, and the only difference was that its marker was in the commit.

Use it for changes that cannot affect the published jar: workflows, tests, the demo,
`consumer-check`.

Documentation no longer needs it. A push to `main` that changed nothing outside `**.md`,
`docs/`, `postman/`, `LICENSE` and `.gitignore` is skipped by the release workflow's guard
job, which prints the file list it decided from. Every case the guard cannot decide
confidently publishes, so it can only ever suppress a release that had nothing in it.

> A related footgun: writing the literal `[skip ci]` anywhere in a commit message, even
> while describing it, makes GitHub suppress the workflow run entirely, before any of this
> repository's own conditions are evaluated.

### Bumping more than a patch

A merge only ever increments the patch. Cut a minor or major version by pushing the tag
yourself, which publishes exactly that version:

```bash
git tag v0.3.0
git push origin v0.3.0
```

A breaking change to the public API needs at least a minor bump. `0.2.0` was one: the
`@Coalesce` TTL attributes became `String` so they could hold property placeholders.

## Branch protection

`main` requires a pull request, and the release workflow works with that: it pushes a tag
and never a branch, and a ruleset targeting `refs/heads/main` does not apply to tags.

This is why the version lives in tags rather than in `gradle.properties`. The workflow used
to commit the next version back to `main` after publishing, which a pull-request rule
rejects outright, leaving every release half-complete: published to Central and tagged, but
with `main` still holding the version that was just consumed. Exempting
`github-actions[bot]` is the obvious fix and it is not available here. GitHub refuses to
add the Actions integration to a ruleset bypass list on a user-owned repository, because a
bypass actor has to belong to the ruleset's owning organisation:

```
Actor GitHub Actions integration must be part of the ruleset source or owner organization
```

## Before opening a pull request

```bash
./gradlew build
```

Needs a Redis on `localhost:6379`. Without one the integration tests skip themselves and the
build passes having proved nothing about coalescing, which is the only thing this library
does.

After a release, verify what actually reached Maven Central:

```bash
./gradlew -p consumer-check test -PcoalesceVersion=<version>
```
