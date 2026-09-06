# Releasing to Maven Central

The published artifact is `net.bitsar:coalesce-spring-boot-starter`. The `coalesce-demo`
module is deliberately not published. It has no `maven-publish` plugin, so no release task
can reach it.

## One-time setup

### 1. Claim the `net.bitsar` namespace

Sign in at [central.sonatype.com](https://central.sonatype.com) → **Namespaces** → **Add
Namespace** → `net.bitsar`. Verification is a DNS check: add the `TXT` record the Portal
shows you to `bitsar.net`, then press **Verify**. This is why the groupId has to be a domain
you control. Central will not accept `com.example` or any namespace you cannot prove.

### 2. Generate a publishing token

**Account** → **Generate User Token**. It gives you a username/password pair: these are the
`CENTRAL_USERNAME` / `CENTRAL_PASSWORD` used below, not your portal login.

### 3. Create a PGP key and publish it

```bash
gpg --full-generate-key                       # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long    # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Central verifies signatures against a public keyserver, so the send-keys step is required,
not optional. Export the private key for Gradle:

```bash
gpg --armor --export-secret-keys <KEY_ID> > ~/coalesce-signing-key.asc
```

### 4. Tell Gradle about the key

Put these in `~/.gradle/gradle.properties`, never in the repo, which is why
`gradle-local.properties` and `*.asc` are in `.gitignore`:

```properties
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signingPassword=your-key-passphrase
```

Newlines in a properties file must be written as literal `\n`. The environment variables
`SIGNING_KEY` and `SIGNING_PASSWORD` work too and are easier in CI, where the key can be
pasted into a secret verbatim.

## Releasing from CI (the normal path)

`.github/workflows/release.yml` does everything below automatically. Add four repository
secrets under **Settings → Secrets and variables → Actions**:

| secret | value |
|---|---|
| `SIGNING_KEY` | the armoured private key, pasted whole (`gpg --armor --export-secret-keys <KEY_ID>`), including the `BEGIN`/`END` lines |
| `SIGNING_PASSWORD` | the key's passphrase; add it as an empty secret if the key has none |
| `CENTRAL_USERNAME` | username half of the portal user token |
| `CENTRAL_PASSWORD` | password half of the portal user token |

Unlike `~/.gradle/gradle.properties`, a GitHub secret takes real newlines: paste the key
exactly as `gpg` printed it, with no `\n` escaping.

### How a release happens

**Every merge to `main` publishes a permanent release.** The workflow works out the version
from the newest `v*` tag, builds, tests against a real Redis, signs, uploads to the Central
Portal with `publishingType=AUTOMATIC`, waits until Central reports `PUBLISHED`, tags
`v<version>` and creates the GitHub Release.

The tag is the only source of truth for the version. A merge publishes the next patch after
the newest tag, so the number for the following release is already recorded the moment this
one is tagged, and nothing has to be committed back to `main`.

```
v0.1.1 is newest  ->  merge publishes 0.1.2, tags v0.1.2
v0.1.2 is newest  ->  merge publishes 0.1.3, tags v0.1.3
```

`version` in `gradle.properties` is a placeholder for local builds and is never published.
It is deliberately not a valid release version, so `centralBundle` fails rather than
building a bundle from it. Pass a real version when you need one locally:

```bash
./gradlew -Pversion=1.2.3 :coalesce-spring-boot-starter:publishToMavenLocal
```

The README install snippets are no longer rewritten automatically, since that needed the
push to `main` this design removes. Update them by hand when the pinned version drifts far
enough to matter; the Maven Central badge at the top of the README always shows the current
version.

### What that costs you

Maven Central versions are immutable: they cannot be deleted, replaced, or reused. Every
merge burns a patch number permanently, including a README typo fix. A bad merge is public
and cannot be withdrawn, only superseded by another release.

To merge without publishing, put `[skip release]` in the commit message:

```bash
git commit -m "Fix a typo in the README [skip release]"
```

A merge only ever increments the patch. Cut a minor or major version by pushing the tag,
which publishes exactly that version:

```bash
git tag v0.3.0
git push origin v0.3.0
```

### Why a release does not trigger another one

The workflow pushes a tag and nothing else. The tag push does trigger the workflow again,
on the tag rather than the branch, and that run releases exactly the version the tag names,
which is already on Central by then. It stops in about twenty seconds at the
already-published check rather than uploading a duplicate.

Pushing the tag with `GITHUB_TOKEN` suppresses that second run entirely in practice, since
pushes made with that token do not trigger workflows.

Concurrency is `release-to-central` with `cancel-in-progress: false`: two merges landing
close together queue rather than racing, since both would otherwise compute the same next
patch from the same newest tag and the second upload would be rejected as a duplicate.

### Releasing without merging

Pushing a `v*` tag releases exactly that version. This is how a minor or major release is
cut, and it is the only way to publish a version that is not the next patch.

**Actions → Release to Maven Central → Run workflow** does the same from the current `main`,
and lets you choose `USER_MANAGED` to hold the deployment for a manual Publish click instead
of releasing immediately.

If a version is somehow already on Central, the run detects it in about twenty seconds and
skips publishing with a warning instead of failing two minutes later on a rejected upload.

The rest of this document is the manual equivalent, for a first release you want to watch
by hand or for debugging a failing workflow run.

---

## Each release

### 1. Choose the version

Pass it explicitly with `-Pversion`; `gradle.properties` holds only a local placeholder.
`centralBundle` refuses anything that is not `MAJOR.MINOR.PATCH`, so a snapshot or the
placeholder fails early rather than at upload time. Versions are immutable once published:
a mistake needs a new version, not a re-upload.

### 2. Build and verify the bundle

```bash
./gradlew clean build centralBundle -Pversion=0.3.0
```

That runs the tests, builds the jar, sources jar and javadoc jar, signs all four plus the
POM, and zips them into
`coalesce-spring-boot-starter/build/coalesce-spring-boot-starter-<version>-bundle.zip`.

Check what you are about to publish:

```bash
unzip -l coalesce-spring-boot-starter/build/coalesce-spring-boot-starter-*-bundle.zip
```

Every artifact should have `.asc`, `.md5` and `.sha1` siblings, all under
`net/bitsar/coalesce-spring-boot-starter/<version>/`.

### 3. Upload

Either drop the zip into **Publish Component** at
[central.sonatype.com/publishing](https://central.sonatype.com/publishing), or:

```bash
curl --request POST \
  --header "Authorization: Bearer $(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64)" \
  --form bundle=@coalesce-spring-boot-starter/build/coalesce-spring-boot-starter-0.1.0-bundle.zip \
  https://central.sonatype.com/api/v1/publisher/upload
```

The response is a deployment id. The deployment sits in `VALIDATED` state until you press
**Publish** in the portal. Nothing is public until then, so a bad bundle can still be
dropped. Expect 10 to 30 minutes before the artifact is resolvable, and a few hours before
it appears in search.

### 4. Verify what actually landed

Once Central has synced (10 to 30 minutes; the portal marks it published sooner than
`repo1.maven.org` serves it):

```bash
./gradlew -p consumer-check test -PcoalesceVersion=<version>
```

This resolves the starter from `mavenCentral()` only and exercises it from `com.acme.app`,
so it fails if auto-configuration did not make it into the jar, the one defect that a green
release build cannot catch, because the in-repo demo would still work through the local
project dependency. Needs a Redis on `localhost:6379`.

### 5. Tag it

```bash
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

## Testing a consumer without publishing

```bash
./gradlew :coalesce-spring-boot-starter:publishToMavenLocal
```

Then add `mavenLocal()` to the consuming project's repositories. This skips signing when no
key is configured, so it works on any machine.
