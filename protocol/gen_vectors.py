"""Generates protocol/test-vectors.json. Independent reference implementation of PROTOCOL.md crypto."""
import base64, hashlib, json, os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

def hkdf(ikm: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=b"tapdrop-v1", info=info).derive(ikm)

master = bytes(range(32))
pair_id = b64u(bytes(range(100, 116)))
enc_key = hkdf(master, b"enc")
auth_key = hkdf(master, b"auth")
auth_token = b64u(auth_key)
token_hash = hashlib.sha256(auth_token.encode()).hexdigest()

msg_id = "6f1c2b9e-3d4a-4c8b-9e2f-0a1b2c3d4e5f"
sender = "phone"
item = {"kind": "url", "url": "https://example.com/a?b=c", "source": "share", "createdAt": 1760000000000, "sender": "Galaxy S24"}
plaintext = json.dumps(item, separators=(",", ":")).encode()
msg_nonce = bytes(range(200, 212))
msg_aad = f"tapdrop/v1/msg/{pair_id}/{sender}/{msg_id}".encode()
msg_ct = AESGCM(enc_key).encrypt(msg_nonce, plaintext, msg_aad)

blob_id = b64u(bytes(range(50, 66)))
blob_plain = b"TapDrop blob test vector \x00\x01\x02\xff"
blob_nonce = bytes(range(220, 232))
blob_aad = f"tapdrop/v1/blob/{pair_id}/{blob_id}".encode()
blob_body = blob_nonce + AESGCM(enc_key).encrypt(blob_nonce, blob_plain, blob_aad)

qr = f"tapdrop://pair?v=1&r=https%3A%2F%2Ftapdrop-relay.example.workers.dev&p={pair_id}&k={b64u(master)}&n=Spectre%20x360"

vectors = {
    "masterKey_b64u": b64u(master),
    "pairId": pair_id,
    "encKey_hex": enc_key.hex(),
    "authKey_hex": auth_key.hex(),
    "authToken": auth_token,
    "tokenHash_hex": token_hash,
    "qrPayload": qr,
    "qrParsed": {"v": 1, "relayUrl": "https://tapdrop-relay.example.workers.dev", "pairId": pair_id, "masterKey_b64u": b64u(master), "deviceName": "Spectre x360"},
    "message": {
        "id": msg_id, "from": sender,
        "aad": msg_aad.decode(),
        "plaintext": plaintext.decode(),
        "nonce_b64u": b64u(msg_nonce),
        "envelope": {"v": 1, "n": b64u(msg_nonce), "c": b64u(msg_ct)},
    },
    "blob": {
        "id": blob_id,
        "aad": blob_aad.decode(),
        "plaintext_hex": blob_plain.hex(),
        "plaintext_sha256_hex": hashlib.sha256(blob_plain).hexdigest(),
        "body_hex": blob_body.hex(),
    },
}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test-vectors.json")
with open(out, "w") as f:
    json.dump(vectors, f, indent=2)
    f.write("\n")
print(out)
