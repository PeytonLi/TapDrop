# TapDrop

Instant, reliable sharing between a Windows laptop (HP Spectre x360) and an Android phone (Galaxy S24), on any
network (including campus Wi-Fi with client isolation), via an end-to-end encrypted Cloudflare relay.

| Path | What |
|---|---|
| `protocol/` | Protocol spec + crypto test vectors (source of truth) |
| `relay/` | Cloudflare Worker + Durable Object relay |
| `windows/` | .NET 8 core library, CLI, and Windows tray app |
| `android/` | Kotlin Android app |

Phase 1: share sheet / Quick Settings tile / hotkey / Send-to sharing. Phase 2: magnet-tap gesture (phone
magnetometer + accelerometer at the laptop's right edge). Phase 3: laptop → phone gestures, LAN fast path.
