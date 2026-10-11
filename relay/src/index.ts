import { DurableObject } from "cloudflare:workers";

export interface Env {
  PAIRS: DurableObjectNamespace<PairDO>;
  MAX_BLOB_BYTES?: string;
  MAX_QUEUE_MESSAGES?: string;
}

const PAIR_ID = /^[A-Za-z0-9_-]{22}$/;
const BLOB_ID = /^[A-Za-z0-9_-]{22}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/v1/health" && request.method === "GET") {
      return Response.json({ ok: true, protocol: 1 });
    }

    const match = url.pathname.match(/^\/v1\/pairs\/([^/]+)(?:\/(register|ws|blobs\/([^/]+)))?$/);
    if (!match) return new Response("Not found", { status: 404 });
    if (!PAIR_ID.test(match[1])) return new Response("Invalid pair id", { status: 400 });
    if (match[3] !== undefined && !BLOB_ID.test(match[3])) {
      return new Response("Invalid blob id", { status: 400 });
    }

    const stub = env.PAIRS.get(env.PAIRS.idFromName(match[1]));
    return stub.fetch(request);
  },
};

const CHUNK_BYTES = 1024 * 1024;
const DAY = 24 * 60 * 60 * 1000;
const WEEK = 7 * DAY;
const SEND_LIMIT = 64 * 1024;
const DEFAULT_QUEUE_LIMIT = 500;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type Role = "laptop" | "phone";
type QueueRow = { id: string; to_role: Role; from_role: Role; ts: number; env: string };
type BlobRow = { id: string; created_at: number; size: number };

export class PairDO extends DurableObject<Env> {
  private readonly sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.initializeSchema();
    this.ctx.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'),
    );
  }

  private initializeSchema(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    this.sql.exec(
      "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, to_role TEXT NOT NULL, from_role TEXT NOT NULL, ts INTEGER NOT NULL, env TEXT NOT NULL)",
    );
    this.sql.exec(
      "CREATE TABLE IF NOT EXISTS blobs (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, size INTEGER NOT NULL)",
    );
    this.sql.exec(
      "CREATE TABLE IF NOT EXISTS blob_chunks (blob_id TEXT NOT NULL, seq INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(blob_id, seq))",
    );
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^\/v1\/pairs\/[A-Za-z0-9_-]{22}/, "") || "/";
    if (path === "/register" && request.method === "POST") return this.register(request);
    if (path === "/ws" && request.method === "GET") return this.connect(request, url);
    const blobMatch = path.match(/^\/blobs\/([^/]+)$/);
    if (blobMatch) {
      if (request.method === "PUT") return this.putBlob(request, blobMatch[1]);
      if (request.method === "GET") return this.getBlob(request, blobMatch[1]);
      if (request.method === "DELETE") return this.deleteBlob(request, blobMatch[1]);
      return new Response("Method not allowed", { status: 405 });
    }
    if (path === "/" && request.method === "DELETE") {
      if (!(await this.authorized(request))) return new Response("Unauthorized", { status: 401 });
      return this.unpair();
    }
    return new Response("Not found", { status: 404 });
  }

  private async register(request: Request): Promise<Response> {
    const token = bearerToken(request);
    if (!token) return new Response("Unauthorized", { status: 401 });
    const tokenHash = await sha256hex(token);
    const existing = this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'tokenHash'").toArray()[0];
    if (existing) {
      return constantTimeEqual(existing.value, tokenHash)
        ? new Response(null, { status: 200 })
        : new Response("Forbidden", { status: 403 });
    }
    this.sql.exec("INSERT INTO meta (key, value) VALUES ('tokenHash', ?)", tokenHash);
    return new Response(null, { status: 201 });
  }

  private async authorized(request: Request): Promise<boolean> {
    const token = bearerToken(request);
    if (!token) return false;
    const existing = this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'tokenHash'").toArray()[0];
    if (!existing) return false;
    return constantTimeEqual(existing.value, await sha256hex(token));
  }

  private async connect(request: Request, url: URL): Promise<Response> {
    const role = url.searchParams.get("role");
    if (role !== "laptop" && role !== "phone") return new Response("Bad role", { status: 400 });
    if (!(await this.authorized(request))) return new Response("Unauthorized", { status: 401 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    for (const old of this.ctx.getWebSockets(role)) old.close(4000, "Replaced by a new connection");
    this.ctx.acceptWebSocket(server, [role]);

    const peerOnline = this.ctx.getWebSockets(otherRole(role)).length > 0;
    server.send(JSON.stringify({ t: "hello", protocol: 1, peerOnline }));
    const queued = this.sql
      .exec<QueueRow>("SELECT id, to_role, from_role, ts, env FROM messages WHERE to_role = ? ORDER BY ts ASC, rowid ASC", role)
      .toArray();
    for (const row of queued) server.send(JSON.stringify(messageFrame(row)));
    this.broadcast(otherRole(role), { t: "presence", peerOnline: true });
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const role = this.socketRole(ws);
    const text = typeof message === "string" ? message : new TextDecoder().decode(message);
    if (new TextEncoder().encode(text).byteLength > SEND_LIMIT) {
      this.error(ws, undefined, "too_large", "Frame exceeds 64 KiB");
      return;
    }
    let frame: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(text);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("invalid frame");
      frame = parsed as Record<string, unknown>;
    } catch {
      this.error(ws, undefined, "bad_frame", "Frame must be a JSON object");
      return;
    }

    if (frame.t === "ping") {
      ws.send('{"t":"pong"}');
      return;
    }
    if (frame.t === "ack") {
      if (typeof frame.id !== "string") {
        this.error(ws, undefined, "bad_frame", "ack requires an id");
        return;
      }
      const row = this.sql
        .exec<Pick<QueueRow, "from_role">>("SELECT from_role FROM messages WHERE id = ? AND to_role = ?", frame.id, role)
        .toArray()[0];
      if (!row) return;
      this.sql.exec("DELETE FROM messages WHERE id = ? AND to_role = ?", frame.id, role);
      this.sendToRole(row.from_role, { t: "delivered", id: frame.id });
      return;
    }
    if (frame.t !== "send" || typeof frame.id !== "string" || !UUID_V4.test(frame.id) || !isEnvelope(frame.env)) {
      this.error(ws, typeof frame.id === "string" ? frame.id : undefined, "bad_frame", "Invalid send frame");
      return;
    }
    if (this.sql.exec("SELECT 1 FROM messages WHERE id = ?", frame.id).toArray().length) {
      this.error(ws, frame.id, "duplicate_id", "Message id is already queued");
      return;
    }

    const recipient = otherRole(role);
    const queueLimit = positiveInt(this.env.MAX_QUEUE_MESSAGES, DEFAULT_QUEUE_LIMIT);
    const count = this.sql
      .exec<{ count: number }>("SELECT COUNT(*) AS count FROM messages WHERE to_role = ?", recipient)
      .toArray()[0]?.count ?? 0;
    if (count >= queueLimit) {
      this.error(ws, frame.id, "queue_full", "Recipient queue is full");
      return;
    }

    const ts = Date.now();
    const envJson = JSON.stringify(frame.env);
    this.sql.exec(
      "INSERT INTO messages (id, to_role, from_role, ts, env) VALUES (?, ?, ?, ?, ?)",
      frame.id,
      recipient,
      role,
      ts,
      envJson,
    );
    await this.ensureAlarm();
    ws.send(JSON.stringify({ t: "stored", id: frame.id }));
    this.sendToRole(recipient, {
      t: "msg",
      id: frame.id,
      from: role,
      ts,
      env: frame.env,
    });
  }

  webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): void {
    const role = this.socketRole(ws);
    this.broadcast(otherRole(role), { t: "presence", peerOnline: this.ctx.getWebSockets(role).length > 0 });
  }

  webSocketError(ws: WebSocket, _error: unknown): void {
    const role = this.socketRole(ws);
    this.broadcast(otherRole(role), { t: "presence", peerOnline: this.ctx.getWebSockets(role).length > 0 });
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    const oldBlobs = this.sql.exec<{ id: string }>("SELECT id FROM blobs WHERE created_at < ?", now - DAY).toArray();
    for (const row of oldBlobs) this.deleteBlobRows(row.id);
    this.sql.exec("DELETE FROM messages WHERE ts < ?", now - WEEK);

    const nextBlob = this.sql.exec<{ ts: number }>("SELECT MIN(created_at + ?) AS ts FROM blobs", DAY).toArray()[0]?.ts;
    const nextMessage = this.sql.exec<{ ts: number }>("SELECT MIN(ts + ?) AS ts FROM messages", WEEK).toArray()[0]?.ts;
    const expiries = [nextBlob, nextMessage].filter((value): value is number => typeof value === "number");
    if (expiries.length) await this.ctx.storage.setAlarm(Math.max(now + 1000, Math.min(...expiries)));
  }

  private async putBlob(request: Request, id: string): Promise<Response> {
    if (!(await this.authorized(request))) return new Response("Unauthorized", { status: 401 });
    if (!BLOB_ID.test(id)) return new Response("Invalid blob id", { status: 400 });
    if (this.sql.exec("SELECT 1 FROM blobs WHERE id = ?", id).toArray().length) {
      return new Response("Blob already exists", { status: 409 });
    }
    const maxBytes = positiveInt(this.env.MAX_BLOB_BYTES, 104857600);
    const contentLength = request.headers.get("Content-Length");
    if (contentLength !== null) {
      const declared = Number(contentLength);
      if (!Number.isSafeInteger(declared) || declared < 0) return new Response("Invalid Content-Length", { status: 400 });
      if (declared > maxBytes) return new Response("Blob too large", { status: 413 });
    }
    if (!request.body) return new Response("Request body required", { status: 400 });

    let total = 0;
    let seq = 0;
    let pending = new Uint8Array(CHUNK_BYTES);
    let used = 0;
    const reader = request.body.getReader();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > maxBytes) {
          await reader.cancel();
          this.deleteBlobRows(id);
          return new Response("Blob too large", { status: 413 });
        }
        let offset = 0;
        while (offset < value.byteLength) {
          const take = Math.min(CHUNK_BYTES - used, value.byteLength - offset);
          pending.set(value.subarray(offset, offset + take), used);
          used += take;
          offset += take;
          if (used === CHUNK_BYTES) {
            this.sql.exec("INSERT INTO blob_chunks (blob_id, seq, data) VALUES (?, ?, ?)", id, seq++, asArrayBuffer(pending));
            pending = new Uint8Array(CHUNK_BYTES);
            used = 0;
          }
        }
      }
      if (used) this.sql.exec(
        "INSERT INTO blob_chunks (blob_id, seq, data) VALUES (?, ?, ?)",
        id,
        seq,
        asArrayBuffer(pending.slice(0, used)),
      );
      if (contentLength !== null && Number(contentLength) !== total) {
        this.deleteBlobRows(id);
        return new Response("Content-Length mismatch", { status: 400 });
      }
      this.sql.exec("INSERT INTO blobs (id, created_at, size) VALUES (?, ?, ?)", id, Date.now(), total);
      await this.ensureAlarm();
      return new Response(null, { status: 201 });
    } catch (error) {
      this.deleteBlobRows(id);
      throw error;
    } finally {
      reader.releaseLock();
    }
  }

  private async getBlob(request: Request, id: string): Promise<Response> {
    if (!(await this.authorized(request))) return new Response("Unauthorized", { status: 401 });
    if (!BLOB_ID.test(id)) return new Response("Invalid blob id", { status: 400 });
    const blob = this.sql.exec<BlobRow>("SELECT id, created_at, size FROM blobs WHERE id = ?", id).toArray()[0];
    if (!blob) return new Response("Not found", { status: 404 });
    let seq = 0;
    const sql = this.sql;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        const row = sql.exec<{ data: ArrayBuffer }>(
          "SELECT data FROM blob_chunks WHERE blob_id = ? AND seq = ?",
          id,
          seq++,
        ).toArray()[0];
        if (!row) {
          controller.close();
          return;
        }
        controller.enqueue(new Uint8Array(row.data));
      },
    });
    return new Response(stream, {
      status: 200,
      headers: { "Content-Type": "application/octet-stream", "Content-Length": String(blob.size) },
    });
  }

  private async deleteBlob(request: Request, id: string): Promise<Response> {
    if (!(await this.authorized(request))) return new Response("Unauthorized", { status: 401 });
    if (!BLOB_ID.test(id)) return new Response("Invalid blob id", { status: 400 });
    this.deleteBlobRows(id);
    return new Response(null, { status: 204 });
  }

  private async unpair(): Promise<Response> {
    const sockets = [...this.ctx.getWebSockets()];
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.initializeSchema();
    for (const socket of sockets) socket.close(4001, "Pair deleted");
    return new Response(null, { status: 204 });
  }

  private deleteBlobRows(id: string): void {
    this.sql.exec("DELETE FROM blob_chunks WHERE blob_id = ?", id);
    this.sql.exec("DELETE FROM blobs WHERE id = ?", id);
  }

  private async ensureAlarm(): Promise<void> {
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now() + DAY);
  }

  private socketRole(ws: WebSocket): Role {
    const role = this.ctx.getTags(ws)[0];
    return role === "phone" ? "phone" : "laptop";
  }

  private broadcast(role: Role, frame: object): void {
    this.sendToRole(role, frame);
  }

  private sendToRole(role: Role, frame: object): void {
    const encoded = JSON.stringify(frame);
    for (const socket of this.ctx.getWebSockets(role)) {
      try {
        socket.send(encoded);
      } catch {
        socket.close(1011, "Send failed");
      }
    }
  }

  private error(ws: WebSocket, id: string | undefined, code: string, message: string): void {
    ws.send(JSON.stringify({ t: "error", ...(id ? { id } : {}), code, message }));
  }
}

function messageFrame(row: QueueRow): object {
  return { t: "msg", id: row.id, from: row.from_role, ts: row.ts, env: JSON.parse(row.env) };
}

function otherRole(role: Role): Role {
  return role === "laptop" ? "phone" : "laptop";
}

function bearerToken(request: Request): string | null {
  const header = request.headers.get("Authorization");
  return header?.startsWith("Bearer ") ? header.slice(7) || null : null;
}

async function sha256hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(left: string, right: string): boolean {
  let mismatch = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index++) {
    mismatch |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return mismatch === 0;
}

function positiveInt(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function asArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.slice().buffer as ArrayBuffer;
}

function isEnvelope(value: unknown): value is { v: 1; n: string; c: string } {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const envelope = value as Record<string, unknown>;
  return envelope.v === 1 && typeof envelope.n === "string" && typeof envelope.c === "string";
}
