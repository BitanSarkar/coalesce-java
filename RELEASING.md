# Releasing to Maven Central

The published artifact is `net.bitsar:coalesce-spring-boot-starter`. The `coalesce-demo`
module is deliberately not published — it has no `maven-publish` plugin, so no release task
can reach it.

## One-time setup

### 1. Claim the `net.bitsar` namespace

Sign in at [central.sonatype.com](https://central.sonatype.com) → **Namespaces** → **Add
Namespace** → `net.bitsar`. Verification is a DNS check: add the `TXT` record the Portal
shows you to `bitsar.net`, then press **Verify**. This is why the groupId has to be a domain
you control — Central will not accept `com.example` or any namespace you cannot prove.

### 2. Generate a publishing token

**Account** → **Generate User Token**. It gives you a username/password pair — these are the
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

Put these in `~/.gradle/gradle.properties` — **never** in the repo, which is why
`gradle-local.properties` and `*.asc` are in `.gitignore`:

```properties
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signingPassword=your-key-passphrase
```

Newlines in a properties file must be written as literal `\n`. The environment variables
`SIGNING_KEY` and `SIGNING_PASSWORD` work too and are easier in CI, where the key can be
pasted into a secret verbatim.

## Each release

### 1. Set the version

`version` in `gradle.properties` is the single source of truth. Central rejects anything
ending in `-SNAPSHOT`, and `centralBundle` fails early rather than letting you find out at
upload time. Versions are immutable once published — a mistake needs a new version, not a
re-upload.

### 2. Build and verify the bundle

```bash
./gradlew clean build centralBundle
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
**Publish** in the portal — nothing is public until then, so a bad bundle can still be
dropped. Expect 10–30 minutes before the artifact is resolvable, and a few hours before it
appears in search.

### 4. Tag it

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
