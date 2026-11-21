# Publishing the SDK to Maven Central

How a version of the SDK gets to Maven Central, for the maintainer. The build side is one convention plugin,
`llamabro.publish` in `sdk/build-logic` ([D133](decisions.md#d133-the-sdk-is-published-to-maven-central-through-the-central-portal-with-the-vanniktech-plugin));
this page is the part done by hand. The upload runs from a laptop or from the release workflow on a version tag
(below); either way the build never releases by itself: an upload is a deployment that waits in the Central Portal
until it is published or dropped there.

A published version cannot be changed or removed. Check the staged files before pressing Publish.

## What is published

Nine modules, all `io.github.whyisitworking:<module>:<version>`: `llamabro`, `llamabro-typed`, `llamabro-typed-ksp`,
`llamabro-hub`, `llamabro-hub-work`, `llamabro-hexagon`, `llamabro-opencl`, `llamabro-testing`, `llamabro-hub-testing`.
Each is the release build with a sources jar, the placeholder javadoc jar Maven Central accepts, a POM and Gradle
module metadata; the Android modules are AARs and `llamabro-typed-ksp` is a jar.

The version is one line, `llamabro` under `[versions]` in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).
A version ending in `-SNAPSHOT` is not a release; anything else (`2.0.0-alpha`, `2.0.0`) is.

## One-time setup

1. **A Central Portal account.** Sign in at <https://central.sonatype.com> with the GitHub account `whyisitworking`.
2. **The namespace.** Signing up with GitHub usually verifies `io.github.whyisitworking` by itself; it then shows as
   verified under <https://central.sonatype.com/publishing/namespaces>. If it does not, add it there, create the
   temporary public GitHub repository named after the verification key the Portal shows, press Verify Namespace, and
   delete the repository once the namespace is verified.
3. **A GPG key.** `gpg --gen-key`, then send the public key to a key server Central reads:
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>` (`keys.openpgp.org` and `pgp.mit.edu` also work).
   Central verifies against the primary key.
4. **A user token.** <https://central.sonatype.com/usertoken>, Generate User Token. It shows a username and a password
   once; they are the upload credentials, not the account's login.

## Properties

The build reads these as Gradle properties and nothing else. Keep them in `~/.gradle/gradle.properties` or pass them as
environment variables named `ORG_GRADLE_PROJECT_<property>`; never in this repository.

| Property | What it is |
|---|---|
| `mavenCentralUsername` | the user token's username |
| `mavenCentralPassword` | the user token's password |
| `signingInMemoryKey` | the private key, ASCII-armoured: the whole output of `gpg --export-secret-keys --armor <KEYID>` |
| `signingInMemoryKeyPassword` | the key's passphrase |
| `signingInMemoryKeyId` | optional: the key's ID, when the export holds more than one |

The armoured key has many lines, so the environment variable is the simple way to pass it:

```bash
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --export-secret-keys --armor <KEYID>)"
```

The Gradle signing plugin's key ring file works too, in place of the three `signingInMemory…` properties:
`signing.keyId`, `signing.password` and `signing.secretKeyRingFile`.

With no key set, nothing is signed: `publishToMavenLocal` and every check work without one, and an upload to Maven
Central fails before it starts.

## Releasing from GitHub

[`.github/workflows/release.yml`](../.github/workflows/release.yml) does steps 3 and 4 below on a version tag, after
CI's checks pass, and also releases the demo app (see [CONTRIBUTING.md](../CONTRIBUTING.md#releasing-the-demo-app)). It
needs four repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (the user token),
`SIGNING_KEY` (the armoured private key) and `SIGNING_KEY_PASSWORD`.

```bash
gh secret set MAVEN_CENTRAL_USERNAME
gh secret set MAVEN_CENTRAL_PASSWORD
gpg --export-secret-keys --armor <KEYID> | gh secret set SIGNING_KEY
gh secret set SIGNING_KEY_PASSWORD
```

The tag must be the catalog's version exactly (`2.0.0-alpha`), or the job stops before building. Steps 1 and 2 stay
yours; after the run, go on at step 5. A manual run of the workflow uploads nothing: it publishes to the runner's local
repository and keeps the files as an artifact, signed when the key is set.

## Releasing a version

Docker must be running: the two backend modules are built in Qualcomm's toolchain image (see
[CONTRIBUTING.md](../CONTRIBUTING.md#setup)). Run one Gradle command at a time.

1. Set the version in `gradle/libs.versions.toml`, move the entry in [release-notes.md](release-notes.md) from
   unreleased to that version, and commit. The Hexagon licence item of D133 is decided (publish), on the reading in
   [F179](research/findings.md#f179-the-hexagon-skeletons-carry-only-llamacpps-code-fastrpc-glue-generated-by-qualcomms-idl-compiler-and-inline-code-from-the-sdks-headers-upstream-llamacpp-publishes-the-same-files-in-its-official-releases).
2. Run the checks: `sdk/scripts/check.sh`, and `ANDROID_SERIAL=<serial> sdk/scripts/check.sh --device`.
3. Look at exactly what would be uploaded, signed, without uploading:

   ```bash
   ./gradlew -p sdk publishToMavenLocal
   ```

   The files are under `~/.m2/repository/io/github/whyisitworking/`: for each module an `.aar` (or `.jar`), a
   `-sources.jar`, a `-javadoc.jar`, a `.pom` and a `.module`, each with an `.asc` signature. Delete that folder
   afterwards, or an app on this machine that lists `mavenLocal()` keeps resolving the copy.
4. Stage it. This uploads a deployment and stops; nothing is public yet:

   ```bash
   ./gradlew -p sdk publishToMavenCentral
   ```

5. Check it at <https://central.sonatype.com/publishing/deployments>. The Portal validates the upload (signatures,
   checksums, POM fields, sources and javadoc jars) within a few minutes; a failed deployment lists what is wrong. Look
   through the validated deployment's files.
6. Release it: press **Publish** on the deployment. Or press **Drop** to discard it and start again. The artifacts
   reach Maven Central some minutes after publishing.
7. Tag the commit if the release workflow did not, and check the README's install lines name the new version.

`publishAndReleaseToMavenCentral` uploads and publishes in one step, with no chance to look first. Use it only for a
release whose staged form has already been checked.

## Snapshots

A `-SNAPSHOT` version goes to `https://central.sonatype.com/repository/maven-snapshots/` with the same
`publishToMavenCentral` task, after snapshots are enabled for the namespace in the Portal. Snapshots can be replaced and
are removed after about 90 days. This build signs them like a release: the upload needs the key either way.
