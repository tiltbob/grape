# Bebird camera protocols

Everything below was recovered from the vendor app `com.molink.john.hummingbird`
(Bebird 6.4.54 and 6.0.57): the pure-Java `com.blackbee.libbb.BebirdTube` class for the
newer scopes, and the native `libBBCameraLibs.so` (decompiled with Ghidra) for the older
ones. No vendor code is included in this repository; the protocol was re-implemented from
this description. All integers are little-endian unless stated otherwise.

The scope is a Wi-Fi access point without internet. Android keeps the phone's default
route on mobile data, so every socket in this app is bound to the scope's `Network`
(see `NetworkLink`). Nothing is ever sent to any address other than the scope's.

## Which protocol does my scope speak?

| Generation | Vendor name | Typical models | Address | Discovery |
|---|---|---|---|---|
| "tube" | BB / 黑蜂 (BlackBee) | ES, ESU, UltraX, ESP, Note5, R1, R3, X3, W3, K3, T3, Q3, Master3, Home 30S, Elite14, Max22, TW100 | `192.168.5.1` (also `192.168.10.1`) | broadcast board-info request, or the 58099 beacon |
| "ML" | ML / 模联 (Molink) | M9 Pro, X7 Pro, X17 Pro, Note3, T15, P30, D3, E3, C3/T5, A2/B2, BB1, K10, Mate8, S5, S7, R5 | `192.168.10.123` | ask the fixed address |

The vendor app first tries the ML battery command at `192.168.10.123`; if that fails it
broadcasts the tube board-info request on port 58090. Grape runs both probes concurrently.

---

## Tube protocol (UDP 58080 / 58090 / 58098 / 58099)

| Port | Direction | Purpose |
|---|---|---|
| 58080 | ⇄ | video START/STOP and MJPEG chunk stream |
| 58090 | ⇄ | commands and replies |
| 58098 | ⇄ | orientation sensor stream (optional) |
| 58099 | ← broadcast | JSON status beacon, ~10 Hz |

### Video (58080)

Send `20 36` to start, `20 37` to stop. The scope streams back to the source address and
port of the START. It keeps streaming to every client that ever sent START until that
client sends STOP, so Grape uses a fixed client port (58081 when free), sends STOP before
START, and STOP on exit. The stream also dies after roughly a second unless the client
keeps talking on the command port; polling the battery once a second keeps it alive.
If no frame arrives for 3 s the vendor app sends STOP, waits 100 ms and sends START again.

Each datagram carries one chunk of a JPEG:

| Offset | Size | Meaning |
|---|---|---|
| 0 | 1 | frame id (all chunks of one frame share it) |
| 1 | 1 | 0 = more chunks follow, non-zero = last chunk (also carries angle high bits) |
| 2 | 1 | chunk index, 1-based |
| 3 | 1 | roll angle, low 8 bits (valid on the last chunk) |
| 4 | … | JPEG data |

All chunks of a frame have the payload size of chunk 1 except the last one, so chunk `i`
lands at `(i - 1) * size`. A frame is complete when every index up to the last one was
seen; otherwise it is dropped. Some firmware omits the final `FF D9`; patch it in.

Angle on the last chunk (`b1`, `b3`):

* integer mode (default): `angle = b3 + (b1 == 2 ? 256 : 0)` → 0..359°
* float mode (board info `float_angle` true): `angle = (b3 + (b1 != 15 ? b1 << 8 : 0)) / 10`

Draw the frame rotated clockwise by the angle. The vendor app ignores changes under 3°.

### Commands (58090)

Request/reply datagrams; replies arrive on the socket that sent the request. Timeout 500 ms.

| Request | Reply | Meaning |
|---|---|---|
| `66 39 01 01` | JSON, may span several datagrams, ends with `}` | board info (model, firmware, ssid, brand, `float_angle`, `rotate_angle`, battery details, …) |
| `66 39 02 <json>` | — | update board info |
| `66 39 02` | text | Wi-Fi AP list (station mode) |
| `66 39 03 01 <ssid>` | — | connect to AP |
| `66 39 04 <ssid>` | — | forget AP |
| `66 39 05` / `66 39 06` | text | connected AP / own AP |
| `66 3A` | 4 bytes big-endian | battery: high 16 bits state (0/1 on battery, 2 charging, 3 full, 4 disconnecting), low 16 bits percent |
| `66 3B nn` | — | camera effect |
| `66 3C nn` | — | tip light level 0..100 (applied by `66 3C FF`) |
| `66 3C FF` | — | commit light level |
| `66 3C FE` | 1 byte | query light level |
| `66 3D` | — | start AP |
| `66 3E` | — | "reboot" (switches the scope off on most units) |
| `66 3F a b` | — | LED: `00 01`/`00 00` tip light on/off (sent when the camera screen opens/closes); `02 01`/`02 00` blue status LED |
| `66 40 nn` | 1 byte when `nn = FE` | tweezer status (1 plain tip, 2 tweezer) |
| `66 41 nn` | — | reliability test |
| `66 42` | — | reset to defaults |
| `66 45 nn` | — | motor speed |
| `66 46` | text | brush data |
| `66 55 01` / `66 56 01` | — | disinfection / magnet check |
| `66 72 ab/aa/a3 nn`, `66 73 00` | — | W30 model specifics |

### Sensor (58098)

`86 06 01` starts, `86 06 00` stops a stream of 34-byte packets. Read as big-endian
shorts, index 9 is the roll angle (0..359); indices 13..16 carry extra values for models
with an "eye" display. The vendor app only uses this on two-channel models; the angle in
the video header is enough for the viewer.

### Beacon (58099)

`{"brand":"bebird","model":"ES","mac":"…","ssid":"bebird-ES-XXXXXX","password":"…",
"wifi_encrypt":false,"ipaddr":"192.168.5.1","button":1,"video_on":0,"battery":65636}` —
`battery` is packed like the `66 3A` reply.

---

## ML protocol (UDP 50000 commands, UDP 8030 / TCP 7060 video)

The scope is always `192.168.10.123`. The vendor library runs both video receivers at the
same time and uses whichever produces frames.

### Commands (UDP 50000)

Request, 24 bytes:

| Offset | Size | Meaning |
|---|---|---|
| 0 | u16 | magic `0x9999` |
| 2 | u16 | command |
| 4 | i32 | sequence number (any increasing value) |
| 8 | u32 | parameter for "set" commands, else 0 |
| 12 | 12 | zero padding |

The reply starts with the same magic and command; most "get" replies carry an `i32` at
offset 8. The library sends on a fresh socket with a 200 ms receive timeout and retries
three times.

| Command | Parameter / reply | Meaning |
|---|---|---|
| `0x1002` | i32 @8 | firmware version (the app maps leading digits to a model: 718→Note3, 762→T15, 754→P30, 760→D3, 755→E3, 3083→B1, 73→C3/T5, 724/725→R1/R3, 728→Mate8) |
| `0x1011` | — | legacy battery |
| `0x1015` | i32 @8 | get tip light (0..100) |
| `0x1016` | param | set tip light (0..100) |
| `0x1017` | i32 @8 raw: bit 16 charging, low 16 bits millivolts; u32 @16: if bit 16 set, low 16 bits is a direct percentage | battery. Percent from voltage: `(mV - 3400) / 6`, Note3 `(mV - 3700) / 3`, R3 `(mV - 3600) / 3` |
| `0x1019` | 0 | power off |
| `0x101A` | — | station / SSID info (text, 0x800 buffer) |
| `0x101C` / `0x101D` | 4 bytes packed in the parameter | get / set PWM (LED) status |
| `0x1054` | param | tweezer status |
| `0x1060` | see below | board info |
| `0x1058`, `0x1011` | — | aging test, clear AP params (unused here) |

Board-info reply (up to 0x578 bytes): u32 @8 is the XOR of the 1024 bytes at offset 24,
i32 @12 is the JSON length, the JSON document starts at offset 24. Keys seen: `model`,
`brand`, `hardware`, `uuid`.

### UDP video (8030)

Send the 24-byte request with command `1` (START) and repeat it every 600 ms; send command
`2` (STOP) when done. Each datagram (≤ 0xC00 bytes) is a chunk:

| Offset | Size | Meaning |
|---|---|---|
| 2 | u16 | packet type, 3 = video chunk |
| 8 | u32 | orientation word (see below) |
| 16 | u32 | 0 = one byte of the frame is inverted (see below) |
| 24 | u8 | frame kind (3 = still that must not be dropped) |
| 25 | u32 | frame id |
| 29 | u32 | total JPEG length |
| 33 | u16 | chunk index, 1-based |
| 35 | u16 | chunk count |
| 37 | u16 | payload length of this chunk |
| 39 | u32 | timestamp |
| 47 | u32 | key |
| 51 | … | payload |

Concatenate the chunks in index order (two frames may interleave). If the word at offset
16 is 0, the byte at `encode_index(key, length)` is inverted on the wire and must be
flipped back:

```
u = ((length ^ key) + length + (~length & 1)) ^ length
index = u mod length          (unsigned arithmetic)
```

### TCP video (7060)

Connect; the scope streams `BoundaryS` + 32-byte header + payload + `BoundaryE` repeatedly:

| Offset | Size | Meaning |
|---|---|---|
| 1 | u8 | type, 0 = video (1 = audio) |
| 2 | u8 | index (0 counts as 1) |
| 4 | u32 | payload length |
| 12 | u32 | frame number |
| 20 | u32 | orientation word |
| 24 / 28 | u32 | width / height |
| 32 | … | JPEG; `payload[length / 2]` is inverted on the wire |

A few bytes may follow the JPEG's `FF D9`; search the last 16 bytes for it.

### Orientation word

Let `top = word >> 30`.

* `top ≥ 2`: absolute angle in thousandths of a degree in the low 30 bits.
* word `0` or `0x10001`: no reading.
* otherwise three 10-bit accelerometer axes: `x = w & 0x3FF`, `y = (w >> 10) & 0x3FF`,
  `z = w >> 20`; values ≥ 512 mean `1024 - v` with the opposite sign. Angle =
  `atan(y / x)`, mirrored to `π - a` when `x ≥ 512` and `2π - a` when `y ≥ 512`. The
  library ignores changes below 0.009 rad (0.04 rad when `z < 25`) and only trusts the
  value when `|x| ≥ 64` or `|y| ≥ 64`.
