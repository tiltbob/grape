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

1. Switch the scope on and join its Wi-Fi from Android settings (or use **Pick camera
   Wi-Fi** on Android 10+, which lets you choose a network whose name starts with a prefix
   and keeps the connection private to this app).
2. Tap **Link current Wi-Fi** so the app pins its sockets to that network (Android would
   otherwise route traffic over mobile data because the scope has no internet).
3. Tap **Scan**. Found cameras are listed; tap one to view. You can also type the scope's
   address directly.
4. In the viewer: the slider sets the tip light, **Snapshot** writes a JPEG to
   `Pictures/Grape`, and **Auto-rotate** toggles compensation of the scope's roll angle.

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
  net/           NetworkLink: obtains and pins the scope's Wi-Fi Network
  ui/            connect screen, viewer, CameraView, snapshot saving
docs/PROTOCOL.md protocol notes
```
