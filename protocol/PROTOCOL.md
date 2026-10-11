# TapDrop protocol v1

Two devices (one `laptop`, one `phone`) form a **pair**. A Cloudflare Worker **relay** stores and forwards
end-to-end encrypted messages and blobs. The relay never sees keys or plaintext.

`b64u` = base64url without padding. All strings UTF-8. Reference implementation of all crypto:
[`gen_vectors.py`](gen_vectors.py); implementations MUST pass [`test-vectors.json`](test-vectors.json).

## 1. Pairing (QR, out of band)

The laptop generates:
- `masterKey`: 32 random bytes
- `pairId`: `b64u(16 random bytes)` (22 chars, `[A-Za-z0-9_-]{22}`)

and shows a QR code containing:

```
tapdrop://pair?v=1&r=<urlencoded relay base URL>&p=<pairId>&k=<b64u(masterKey)>&n=<urlencoded laptop name>
```

The phone scans it, stores `relayUrl`, `pairId`, `masterKey`, `deviceName` in secure storage. The QR is the only
key exchange; anyone who sees the QR can join the pair (re-pair to rotate).

### Key derivation
```
encKey    = HKDF-SHA256(ikm = masterKey, salt = "tapdrop-v1", info = "enc",  L = 32)
authKey   = HKDF-SHA256(ikm = masterKey, salt = "tapdrop-v1", info = "auth", L = 32)
authToken = b64u(authKey)                          # sent to relay as Bearer token
tokenHash = hex(SHA-256(ASCII(authToken)))         # what the relay stores
```

## 2. Relay HTTP API

All endpoints except health require `Authorization: Bearer <authToken>`. Requests are routed to a Durable Object
named by `pairId` (`idFromName(pairId)`). Invalid `pairId`/`blobId` format → `400`.

| Method & path | Behavior |
|---|---|
| `GET /v1/health` | `200 {"ok":true,"protocol":1}` |
| `POST /v1/pairs/{pairId}/register` | First call stores `tokenHash` → `201`. Same token again → `200`. Different token → `403`. Called by the laptop when it creates the pair and by the phone after scanning (idempotent). |
| `GET /v1/pairs/{pairId}/ws?role=laptop\|phone` | WebSocket upgrade. `401` if pair unregistered or token mismatch; `400` bad role; `426` if not an upgrade. |
| `PUT /v1/pairs/{pairId}/blobs/{blobId}` | Body = encrypted blob bytes (§5). Max body `100 MiB` → else `413`. Existing id → `409`. Success `201`. |
| `GET /v1/pairs/{pairId}/blobs/{blobId}` | `200` streamed bytes (`application/octet-stream`, `Content-Length`) or `404`. |
| `DELETE /v1/pairs/{pairId}/blobs/{blobId}` | `204` (also if already gone). |
| `DELETE /v1/pairs/{pairId}` | Unpair: wipes all DO state (token, queue, blobs), closes sockets with code `4001`. `204`. |

Auth failure on any authed endpoint → `401`. `blobId` = `b64u(16 random bytes)` chosen by the uploader.

Storage: the relay uses Durable Object **SQLite storage only** (no R2/KV) so it runs on the Workers free plan.
Blobs are stored in ~1 MiB chunks. Blobs expire **24 h** after upload; queued messages expire after **7 days**
(enforced with a DO alarm).

## 3. WebSocket frames

Text frames, one JSON object each, discriminated by `t`. The relay uses the hibernatable WebSocket API.

On connect the relay sends `{"t":"hello","protocol":1,"peerOnline":<bool>}`, then every queued message addressed
to this role, oldest first, as `msg` frames. When a role connects/disconnects the relay sends the other role
`{"t":"presence","peerOnline":<bool>}`. A new connection for a role that is already connected replaces it (old
socket closed with code `4000`).

| Direction | Frame | Meaning |
|---|---|---|
| client → relay | `{"t":"send","id":"<uuid v4>","env":<Envelope>}` | Queue for the other role. Relay replies `{"t":"stored","id"}` and, if the peer is online, forwards immediately. |
| relay → client | `{"t":"msg","id","from":"laptop"\|"phone","ts":<relay ms epoch>,"env":<Envelope>}` | Delivery. May be redelivered until acked. |
| client → relay | `{"t":"ack","id"}` | Recipient processed it. Relay deletes it and sends the sender `{"t":"delivered","id"}` if online. |
| client → relay | `{"t":"ping"}` | Relay answers `{"t":"pong"}` (auto-response, works while hibernated). Clients ping every 25 s and reconnect if no pong within 10 s. |
| relay → client | `{"t":"error","id"?,"code","message"}` | `code` ∈ `bad_frame`, `too_large`, `queue_full`, `duplicate_id`. |

Limits: a `send` frame ≤ **64 KiB**; ≤ **500** queued messages per recipient (`queue_full` beyond that);
duplicate `id` for an already-queued message → `duplicate_id`.

Delivery is **at-least-once**: clients must dedupe by `id` (remember the last 1000 ids) and ack only after the item
is fully processed (blob downloaded and verified). Clients reconnect with exponential backoff (1 s → 30 s max,
with jitter).

## 4. Envelope (message encryption)

```
Envelope = {"v":1,"n":b64u(nonce),"c":b64u(ciphertext || tag)}
nonce    = 12 random bytes
AES-256-GCM(key = encKey, nonce, plaintext = UTF-8 JSON Item,
            aad = ASCII("tapdrop/v1/msg/" + pairId + "/" + fromRole + "/" + id))
```
`fromRole` is the sender's role; the recipient uses the `from` field of the `msg` frame. Decryption failure →
drop the item, still ack (it can never succeed), and log it.

### Item (plaintext JSON)
```jsonc
{
  "kind": "text" | "url" | "image" | "file",
  "text": "...",             // kind=text, when ≤ 32 KiB UTF-8; larger text is sent as a blob with mime text/plain
  "url": "https://...",      // kind=url
  "blob": {                  // kind=image|file (and large text)
    "id": "<blobId>", "name": "IMG_1234.jpg", "mime": "image/jpeg",
    "size": 123456,          // plaintext bytes
    "sha256": "<hex of plaintext>"
  },
  "source": "share" | "tile" | "hotkey" | "sendto" | "tray" | "tap" | "cli",
  "createdAt": 1760000000000, // sender ms epoch
  "sender": "Galaxy S24"      // human-readable device name
}
```
Receivers ignore unknown fields and treat unknown `kind` as an error (ack and drop).

## 5. Blob encryption

```
body = nonce(12) || AES-256-GCM(key = encKey, nonce, plaintext,
                                aad = ASCII("tapdrop/v1/blob/" + pairId + "/" + blobId))
```
Max plaintext **95 MiB** (whole-buffer GCM). Sender flow: encrypt → `PUT` blob → `send` item referencing it.
Receiver flow: `GET` blob → decrypt → verify `sha256` and `size` → save → `ack` message → `DELETE` blob.

## 6. Receiver behavior (informative)

| kind | Laptop (Windows) | Phone (Android) |
|---|---|---|
| text | copy to clipboard + toast | copy to clipboard + notification |
| url | toast with Open/Copy (auto-open if enabled) | notification with Open/Copy |
| image | save to `Pictures\TapDrop`, copy image to clipboard, toast with preview | save to `Pictures/TapDrop` (MediaStore), notification with preview |
| file | save to `Downloads\TapDrop`, toast with Open/Show in folder | save to `Download/TapDrop` (MediaStore), notification with Open |
