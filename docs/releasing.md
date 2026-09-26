# Releasing to Maven Central

scaladock publishes two artifacts under the group `io.github.canardlapin`:
`scaladock-core_3` and `scaladock-fx_3`. The demo is never published.

Everyday builds are `0.1.0-SNAPSHOT`. A release is a Git tag: pushing `vX.Y.Z` runs
[`.github/workflows/release.yml`](../.github/workflows/release.yml), which tests, signs, and
publishes version `X.Y.Z` through sbt's built-in Central Portal support (`publishSigned` stages
under `target/sona-staging`, `sonaRelease` uploads and releases).

## One-time setup

These steps need the `canardlapin` accounts, so they cannot be scripted from the repository.

1. **Claim the namespace.** Sign in to <https://central.sonatype.com> with the `canardlapin`
   GitHub account and add the namespace `io.github.canardlapin`. Namespaces of the form
   `io.github.<github-user>` are verified through that GitHub account.
2. **Create a publishing token.** In the Central Portal, under *View Account → Generate User
   Token*. Its two halves are the `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` secrets.
3. **Create a signing key** and publish its public half:

   ```sh
   gpg --quick-generate-key "canardlapin <brad@duckrabbit.ai>" ed25519 sign 2y
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> | base64 | pbcopy   # the PGP_SECRET value
   ```

4. **Add repository secrets** at *Settings → Secrets and variables → Actions* on
   `canardlapin/scaladock`: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `PGP_SECRET`,
   `PGP_PASSPHRASE`.

## Cutting a release

1. Move the `## Unreleased` section of [CHANGELOG.md](../CHANGELOG.md) under the new version.
2. Check CI is green on `main`, then tag and push:

   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the *Release* workflow. The artifacts appear on Maven Central a few minutes after it
   succeeds.

## Checking a release locally

`sbt publish` stages unsigned artifacts under `target/sona-staging` without uploading anything,
which is enough to inspect the POMs (group, SCM, license, developers; JavaFX as `provided` and
without a platform classifier).
