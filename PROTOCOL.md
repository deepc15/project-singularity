# Singular Protocol v1

The wire protocol between **Singular Cast** (Android, sender) and **Singular Desk**
(Windows, receiver).

## Roles

| Role | App | Listens |
|---|---|---|
| Server | Singular Desk (Windows) | TCP `8787`, UDP `8788` |
| Client | Singular Cast (Android) | — |

The PC listens so that one PC can accept several phones, and so the phone (which
moves between networks) is always the party that initiates.

## Discovery

1. Android broadcasts the ASCII probe `SINGULAR_PROBE/1` to `255.255.255.255:8788`.
2. Every Desk instance unicasts a JSON reply back to the probe's source port:

```json
{ "t": "singular.pc", "proto": 1, "name": "DESKTOP-4KQ1", "port": 8787, "id": "a3f1..." }
```

3. Android lists the replies; the user picks one (or types an IP manually) and opens
   a TCP connection to `port`.

## Framing

Every TCP message is a frame:

```
 0        1                    5
 +--------+--------------------+----------------------------+
 | type   | length (u32 BE)    | payload (length bytes)     |
 +--------+--------------------+----------------------------+
```

`length` counts the payload only. Max payload is 8 MiB; a larger value is a
protocol error and the peer must close the connection.

| type | name | direction | payload |
|---|---|---|---|
| `0x01` | `CONTROL` | both | UTF-8 JSON object with a `t` discriminator |
| `0x02` | `VIDEO_CONFIG` | A→P | `streamId:u32` + H.264 SPS/PPS in Annex-B |
| `0x03` | `VIDEO_FRAME` | A→P | `streamId:u32` + `flags:u8` + `ptsUs:u64` + Annex-B access unit |
| `0x04` | `AUDIO_CONFIG` | A→P | reserved, not implemented in v1 |
| `0x05` | `AUDIO_FRAME` | A→P | reserved, not implemented in v1 |

`flags` bit 0 (`0x01`) marks a keyframe (IDR). All other bits are reserved and
must be zero.

Video is baseline-to-high-profile H.264 in **Annex-B** byte-stream format, which is
what `MediaCodec` produces and what `VideoDecoder` accepts when no `description`
is supplied.

## Control messages

### Android → PC

| `t` | Payload | Meaning |
|---|---|---|
| `hello` | `proto`, `device:{name,model,sdk,w,h,dpi}`, `caps:{privileged,accessibility,mirror}` | First frame after connect |
| `apps` | `list:[{pkg,label,running,recent,iconPng?}]` | Full app inventory (icons are base64 PNG, sent once per package) |
| `stream.start` | `id`, `app:{pkg,label}`, `w`, `h`, `dpi`, `mode:"display"\|"mirror"` | A new cast is starting; a `VIDEO_CONFIG` for `id` follows |
| `stream.stop` | `id`, `reason` | Cast ended (`recalled`, `app-closed`, `error`, `disconnect`) |
| `stream.resized` | `id`, `w`, `h`, `dpi` | Virtual display geometry changed; a new `VIDEO_CONFIG` follows |
| `ime.show` | `id` | The cast app focused an editable field — phone should raise its keyboard |
| `ime.hide` | `id` | Editable focus lost |
| `toast` | `text`, `level:"info"\|"warn"\|"error"` | Surface a message on the PC |
| `pong` | `ts` | Reply to `ping`, echoing `ts` |

### PC → Android

| `t` | Payload | Meaning |
|---|---|---|
| `hello.ack` | `proto`, `name`, `tile:{w,h,dpi}` | Accepted; `tile` is the preferred virtual-display geometry |
| `apps.refresh` | — | Re-send the inventory |
| `stream.request` | `pkg` | PC-initiated cast of a package |
| `stream.recall` | `id` | Send this app back to the phone |
| `stream.geometry` | `id`, `w`, `h`, `dpi` | Tile was resized — resize the virtual display to match |
| `stream.bitrate` | `id`, `bps` | Change encoder bitrate |
| `stream.keyframe` | `id` | Request an immediate IDR (used after a decoder reset) |
| `input.touch` | `id`, `action:"down"\|"move"\|"up"\|"cancel"`, `x`, `y`, `pointer` | Pointer event; `x`/`y` are normalized `0.0–1.0` of the display |
| `input.scroll` | `id`, `x`, `y`, `dx`, `dy` | Wheel; `dx`/`dy` in notches, positive = right/down |
| `input.key` | `id`, `action:"down"\|"up"`, `keyCode`, `meta` | Android `KeyEvent` keycode and meta-state bitmask |
| `input.text` | `id`, `text` | Commit a UTF-8 string into the focused field |
| `ping` | `ts` | Liveness probe every 3 s |

Coordinates are normalized so that a tile resize or a display rotation never
desynchronises the two sides.

## Lifecycle

```
Android                                     PC
  |-- TCP connect ------------------------->|
  |-- CONTROL hello ---------------------->|
  |<------------------- CONTROL hello.ack --|
  |-- CONTROL apps ----------------------->|
  |                                         |
  |   (user swipes an app toward the edge)  |
  |-- CONTROL stream.start id=1 ---------->|   tile created, decoder configured
  |-- VIDEO_CONFIG id=1 ------------------>|
  |-- VIDEO_FRAME id=1 (key) ------------->|
  |-- VIDEO_FRAME id=1 ...---------------->|
  |<---------------- CONTROL input.touch ---|   mouse on the tile
  |<---------------- CONTROL input.key -----|   PC keyboard
  |-- CONTROL ime.show id=1 -------------->|   app focused a text field
  |<------------- CONTROL stream.recall ----|   or the phone's "Bring back" button
  |-- CONTROL stream.stop id=1 ----------->|   tile closed
```

A stream id is a monotonically increasing `u32` allocated by Android and never
reused within a connection.

## Error handling

- An unknown `t` is ignored, not fatal — this is how v1 peers tolerate v2 senders.
- A `VIDEO_FRAME` for an unknown `streamId` is dropped.
- The PC drops frames until the first keyframe of a stream arrives.
- If no `pong` is received for 9 s, the peer is considered gone and the
  connection is closed; Android retries with backoff.
