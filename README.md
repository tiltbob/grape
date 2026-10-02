# EarDigger

EarDigger (repository `grape`, package `io.github.tiltbob.grape`) is a clean-room Android
viewer for Bebird Wi-Fi ear scopes (the cameras the
`com.molink.john.hummingbird` "Bebird" app drives). It connects to the scope's own Wi-Fi,
shows the live picture, reads the battery, sets the tip light and saves snapshots.

That is all it does. There are no accounts, no analytics, no crash reporting, no push
messaging, no update checks, no ads and no cloud: the only network traffic the app
generates is UDP/TCP to the scope's own address on its own Wi-Fi. The `INTERNET`
permission is required by Android for any socket at all; nothing else is requested.

## Supported scopes

Both protocol generations of the vendor app are implemented (see
[docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire formats):

* **tube** (UDP 58080/58090, `192.168.5.1`): ES, ESU, UltraX, Note5, R1, R3, X3, W3 and
  other recent models;
* **ML** (UDP 50000 + UDP 8030 / TCP 7060, `192.168.10.123`): M9 Pro, X17 Pro, Note3,
  T15, P30, D3, E3 and other older models.

The protocol code was reconstructed from the vendor APK (Java decompile of the tube
classes, Ghidra decompile of `libBBCameraLibs.so` for ML). It has not yet been exercised
against hardware from this environment; reports and packet captures are welcome.

## Using it

1. Switch the scope on and tap **Find nearby**. The app listens for the scope over
   Bluetooth LE and in the Wi-Fi scan results (Android asks for the nearby-devices /
   location permissions this needs; nothing leaves the phone). Scopes appear in a list.
2. Tap a scope. The app joins its Wi-Fi itself (on Android 10+ the connection is private to
   the app and Android remembers your one-time approval, so the next time it is automatic),
   finds the camera on that network and opens the live view. A scope you used before is
   rejoined without a tap when it shows up again.
3. If you prefer, join the scope's Wi-Fi from system settings and tap **Link current
   Wi-Fi**, or use **Pick camera Wi-Fi** (Android 10+). Then **Scan**.
4. In the viewer: the slider sets the tip light, **Snapshot** writes a JPEG to
   `Pictures/EarDigger`, and **Auto-rotate** toggles compensation of the scope's roll angle.

The link step matters because the scope has no internet: without pinning its sockets to
that Wi-Fi network Android would route the app's traffic over mobile data.

## Installing with Obtainium

Releases are published on GitHub with a signed APK attached, which is what
[Obtainium](https://github.com/ImranR98/Obtainium) consumes. In Obtainium choose
**Add App**, paste this repository's URL (`https://github.com/tiltbob/grape`) and add it;
Obtainium then installs `eardigger-<version>.apk` from the latest release and notifies you of
new ones. If you want to be strict about which asset it picks, set the APK filter to
`eardigger-.*\.apk`.

### Cutting a release (maintainers)

The release workflow (`.github/workflows/release.yml`) runs when a tag like `v1.2.3` is
pushed: it runs the unit tests, builds the release APK signed with the project key, checks
the signature, and creates a GitHub Release named after the tag with `eardigger-1.2.3.apk` and
its SHA-256. The tag decides both the version name and the version code
(`major * 1000000 + minor * 1000 + patch`), so tags must be strictly increasing. A
pre-release suffix such as `v1.2.3-rc1` is accepted but shares its version code with the
final `v1.2.3`.

One-time setup, from a laptop with `gh` logged in (no Java needed, about a minute):

```
gh repo clone tiltbob/grape && cd grape
scripts/setup-signing.sh
```

The script creates a 4096-bit RSA key and a 100-year certificate with `openssl`, writes
them to the repository's Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`) and securely wipes the local copy, so GitHub holds the only
one. It prints the certificate fingerprint for your records. Android only installs
updates signed with the same key, so do not delete those secrets.

Sharing one key across several apps: GitHub has no account-wide Actions secrets for
personal accounts, so pass every app repository in the same run
(`scripts/setup-signing.sh --repo you/app1 --repo you/app2`). With an organization the
secrets live at organization level and new repositories are granted later without
touching the key (`--org ORG --repos app1,app2`, then `--org ORG --grant ORG/app3`).
`--dry-run` shows what would happen without writing anything.

Then:

```
git tag v0.1.0
git push origin v0.1.0
```

Every push and pull request also runs `.github/workflows/ci.yml` (tests, lint, debug
APK as a build artifact).

## Building

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # protocol codec tests
./gradlew assembleRelease      # unsigned unless GRAPE_KEYSTORE_FILE etc. are set
```

Requires JDK 17+ and an Android SDK with platform 35 (`local.properties` → `sdk.dir`).
To sign a local release build, export `GRAPE_KEYSTORE_FILE`, `GRAPE_KEYSTORE_PASSWORD`,
`GRAPE_KEY_ALIAS` and `GRAPE_KEY_PASSWORD`; pass `-PgrapeVersionName=… -PgrapeVersionCode=…`
to override the version.

## Layout

```
app/src/main/java/io/github/tiltbob/grape/
  camera/        CameraClient interface, DeviceInfo, factory
  protocol/tube/ tube protocol: codecs, frame reassembly, client, discovery
  protocol/ml/   ML protocol: codecs, UDP reassembly, TCP parser, client, discovery
  discovery/     runs all discoveries concurrently
  net/           NetworkLink (joins and pins the scope's Wi-Fi), NearbyScanner (BLE + Wi-Fi
                 scan), CameraWifi (naming / BSSID / security rules)
  ui/            connect screen, viewer, CameraView, snapshot saving
docs/PROTOCOL.md protocol notes
```
