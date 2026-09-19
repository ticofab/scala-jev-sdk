# Publishing to Maven Central

This repository is already wired for it. What follows is the one-time account and key setup, then the release loop.

Everything here targets the **Central Portal** (`central.sonatype.com`). The legacy OSSRH endpoints
(`oss.sonatype.org`) were sunset on **2025-06-30**, so older sbt guides no longer apply — Central Portal publishing
needs **sbt 1.11+** and **sbt-ci-release 1.11+**. This build uses sbt 1.12.15 and sbt-ci-release 1.12.1.

## What the build already does

`project/plugins.sbt` pulls in [`sbt-ci-release`](https://github.com/sbt/sbt-ci-release), which bundles:

| Plugin        | Job                                                             |
|---------------|-----------------------------------------------------------------|
| `sbt-dynver`  | derives `version` from the git tag, so no version is hard-coded  |
| `sbt-pgp`     | signs the artifacts                                              |
| `sbt-sonatype`| uploads the bundle and releases it on the Portal                 |

`build.sbt` carries the POM metadata Central requires: `organization`, `homepage`, `licenses`, `developers` and
`scmInfo`. Sources and scaladoc jars are produced by sbt's defaults. Per sbt-ci-release, the build deliberately does
**not** set `version`, `publishTo`, `publishMavenStyle` or `credentials` — the plugin owns those.

Two settings are worth knowing about:

- `organization := "io.github.ticofab"` — the Maven **groupId**, and the namespace you verify below. Change it (and the
  package name) if you would rather publish under a domain you own.
- `versionScheme := Some("early-semver")` — tells downstream builds how to read `0.x` version bumps.

## One-time setup

### 1. Claim the namespace — done

`io.github.ticofab` already shows **Verified** under Publishing Settings → Namespaces, which matches the
`organization` in `build.sbt`. Nothing to do here.

Two things worth knowing for later:

- Namespaces are tied to the **sign-in method**, not the email. Sign in to the Portal the same way you did when you
  verified it (GitHub), or the namespace won't be listed.
- The **Publish Component** button on that page is the manual route — it wants a hand-built signed bundle zip.
  `sbt ci-release` uploads through the Portal's publishing API instead, so you never need that dialog.

Changing to a groupId like `com.ticofab` would mean verifying again, this time with a DNS TXT record on a domain you
own.

### 2. Generate a publishing token

**Account → Generate User Token**. Central hands you a username/password pair — these are *not* your login
credentials, and the password is shown once.

### 3. Create a signing key

Central only accepts signed artifacts. Make a key dedicated to CI:

```bash
gpg --gen-key                     # name + email; keep the passphrase
gpg --list-keys --keyid-format LONG
export LONG_ID=<the long key id>

# Publish the public half so Central can verify the signatures.
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys $LONG_ID

# Export the private half, base64-encoded, for the CI secret.
gpg --armor --export-secret-keys $LONG_ID | base64 | tr -d '\n' | pbcopy
```

If the send fails with `No route to host`, the plain `hkp://` transport (port 11371) is being blocked — a common
ISP/network default. `hkps://` uses port 443 instead. If it still fails after that, `dirmngr` is holding a stale
connection; restart it and retry:

```bash
gpgconf --kill dirmngr
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys $LONG_ID
```

Confirm it landed — by **full fingerprint**, since the short key-id index can lag by a while:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$LONG_ID"   # want 200
```

### 4. Add the GitHub secrets

**Settings → Secrets and variables → Actions** on the repository:

| Secret              | Value                                              |
|---------------------|----------------------------------------------------|
| `PGP_SECRET`        | the base64 blob from step 3                        |
| `PGP_PASSPHRASE`    | that key's passphrase                              |
| `SONATYPE_USERNAME` | the **token** username from step 2                 |
| `SONATYPE_PASSWORD` | the **token** password from step 2                 |

`.github/workflows/release.yml` reads exactly these four.

## Releasing

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

The Release workflow runs `sbt ci-release`, which publishes the signed artifacts and releases the bundle. Within ten minutes or so it is on
[central.sonatype.com](https://central.sonatype.com/artifact/io.github.ticofab/scala-jev-sdk_3), and Maven Central
search picks it up within a few hours.

A push to `main` with no tag publishes a snapshot instead, versioned `0.1.0+3-abc1234-SNAPSHOT` by sbt-dynver — useful
for trying a change downstream before tagging it.

### Before the first tag

- [x] Namespace verified.
- [ ] Token generated, key uploaded, secrets set.
- [ ] `sbt test` green — CI runs it on JDK 17 and 21.
- [ ] `sbt publishLocal`, then resolve `io.github.ticofab %% scala-jev-sdk % 0.1.0-SNAPSHOT` from a scratch project.
- [ ] `developers` in `build.sbt` says what you want to be public — it ships in the POM, email included.
- [ ] `CHANGELOG.md` has an entry for the version.

### Trying it locally first

```bash
sbt publishLocal           # ~/.ivy2/local
sbt publishM2              # ~/.m2/repository, for a Maven consumer
sbt publishSigned          # signs without uploading; checks the key works
```

## Keeping it releasable

- **Binary compatibility.** Under `early-semver`, `0.1.x → 0.1.y` promises binary compatibility. Adding
  [MiMa](https://github.com/lightbend/mima) (`sbt-mima-plugin`) makes the build enforce that instead of trusting
  review, and is worth doing once the API settles.
- **A release cannot be unpublished.** Central is immutable; a mistake is fixed by releasing the next version.
- **Tag only from the default branch**, and only from a commit CI has already run — `ci-release` does not re-run tests.
- **The artifact is Scala-versioned.** It publishes as `scala-jev-sdk_3`, and `%%` in a consumer's build picks it up.
  Adding a Scala version later is harmless; removing one is a breaking change for whoever was on it, so think twice
  before publishing a 2.13 build you would then have to keep.

## References

- [sbt-ci-release](https://github.com/sbt/sbt-ci-release)
- [sbt Reference Manual — Using Sonatype](https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html)
- [Central Portal publishing documentation](https://central.sonatype.org/publish/publish-portal-api/)
- [Central Portal namespace verification](https://central.sonatype.org/register/namespace/)
