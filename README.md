# Grape

A clean-room Android viewer for Bebird Wi-Fi ear scopes (the cameras the
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
   Wi-Fi**, or use **Pick camera Wi-Fi** (Android 10+). Then **Scan**, or type the scope's
   address.
4. In the viewer: the slider sets the tip light, **Snapshot** writes a JPEG to
   `Pictures/Grape`, and **Auto-rotate** toggles compensation of the scope's roll angle.

The link step matters because the scope has no internet: without pinning its sockets to
that Wi-Fi network Android would route the app's traffic over mobile data.

## Building

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # protocol codec tests
```

Requires JDK 17+ and an Android SDK with platform 35 (`local.properties` → `sdk.dir`).

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
