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

Merging to `main` publishes a **permanent** release. The workflow reads the version from
`gradle.properties`, builds, signs, uploads to the Central Portal, waits for Central to
confirm, tags `v<version>`, creates the GitHub Release, then commits the next patch version
back to `main`.

Maven Central versions are immutable — they cannot be deleted, replaced or reused. A merge
burns a patch number forever.

### Merging without publishing

Put `[skip release]` in the merge commit message. For a squash merge that is the pull
request title, so a chore that does not change the artifact can be titled:

```
Update GitHub Actions to Node 24 runtimes [skip release]
```

Use it for changes that cannot affect the published jar: workflows, README, tests, the
demo, `consumer-check`.

> Note the related footgun: writing the literal `[skip ci]` anywhere in a commit message —
> even while describing it — makes GitHub suppress the workflow run entirely, before any of
> this repository's own conditions are evaluated.

### Bumping more than a patch

The workflow only auto-increments the patch. For a minor or major release, edit `version`
in `gradle.properties` in your pull request; the merge publishes what the file says.

A breaking change to the public API needs at least a minor bump. `0.2.0` was one: the
`@Coalesce` TTL attributes became `String` so they could hold property placeholders.

## Branch protection

If you protect `main` with "require a pull request before merging", **exempt
`github-actions[bot]`** or the release workflow cannot push its version-bump commit and
every release will half-complete: published to Central and tagged, but with `main` still
holding the version that was just consumed.

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
