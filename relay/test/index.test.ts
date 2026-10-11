import { SELF, env, runInDurableObject, runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import type { Env as WorkerEnv } from "../src/index";

declare module "cloudflare:test" {
  interface ProvidedEnv extends WorkerEnv {}
}

const TOKEN = "vector-auth-token";
const AUTH = { Authorization: `Bearer ${TOKEN}` };
const openSockets: WebSocket[] = [];
type FrameWaiter = {
  resolve: (frame: Record<string, any>) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};
type FrameQueue = { frames: Record<string, any>[]; waiters: FrameWaiter[] };
const frameQueues = new WeakMap<WebSocket, FrameQueue>();

afterEach(async () => {
  await Promise.all(openSockets.splice(0).map(socket => new Promise<void>((resolve) => {
    if (socket.readyState === WebSocket.CLOSED) {
      resolve();
      return;
    }
    const timer = setTimeout(resolve, 100);
    socket.addEventListener("close", () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
    try {
      socket.close();
    } catch {
      clearTimeout(timer);
      resolve();
    }
  })));
});

describe("relay HTTP API", () => {
  it("serves health and validates pair and blob identifiers", async () => {
    const health = await SELF.fetch("https://tapdrop.test/v1/health");
    expect(health.status).toBe(200);
    expect(await health.json()).toEqual({ ok: true, protocol: 1 });
    expect((await SELF.fetch("https://tapdrop.test/v1/pairs/invalid/register", { method: "POST" })).status).toBe(400);
    expect((await SELF.fetch(`https://tapdrop.test/v1/pairs/${pairId()}/blobs/not-valid`, { method: "GET" })).status).toBe(400);
  });

  it("registers once, idempotently, and rejects a different token", async () => {
    const id = pairId();
    expect((await register(id)).status).toBe(201);
    expect((await register(id)).status).toBe(200);
    expect((await request(`/v1/pairs/${id}/register`, {
      method: "POST",
      headers: { Authorization: "Bearer another-token" },
    })).status).toBe(403);
  });

  it("requires registration and the correct token for WebSockets, and validates role", async () => {
    const id = pairId();
    expect((await websocket(id, "laptop", TOKEN)).status).toBe(401);
    await register(id);
    expect((await websocket(id, "laptop", "wrong-token")).status).toBe(401);
    expect((await websocket(id, "tablet", TOKEN)).status).toBe(400);
    expect((await request(`/v1/pairs/${id}/ws?role=laptop`, { method: "GET", headers: AUTH })).status).toBe(426);
  });

  it("stores and streams an exact multi-chunk blob and handles not-found, conflict, limit and delete", async () => {
    const id = pairId();
    await register(id);
    const blobId = pairId();
    const bytes = new Uint8Array(3 * 1024 * 1024 + 37);
    for (let index = 0; index < bytes.length; index++) bytes[index] = (index * 31) % 251;
    const path = `/v1/pairs/${id}/blobs/${blobId}`;

    expect((await request(path, { method: "GET", headers: AUTH })).status).toBe(404);
    expect((await request(path, { method: "PUT", headers: AUTH, body: bytes })).status).toBe(201);
    expect((await request(path, { method: "PUT", headers: AUTH, body: bytes })).status).toBe(409);
    const get = await request(path, { method: "GET", headers: AUTH });
    expect(get.status).toBe(200);
    expect(get.headers.get("Content-Length")).toBe(String(bytes.length));
    expect(new Uint8Array(await get.arrayBuffer())).toEqual(bytes);
    expect((await request(path, { method: "DELETE", headers: AUTH })).status).toBe(204);
    expect((await request(path, { method: "GET", headers: AUTH })).status).toBe(404);

    const tooLarge = pairId();
    const tooLargePath = `/v1/pairs/${id}/blobs/${tooLarge}`;
    const limit = Number(env.MAX_BLOB_BYTES);
    expect((await request(tooLargePath, {
      method: "PUT",
      headers: { ...AUTH, "Content-Length": String(limit + 1) },
      body: new Uint8Array(limit + 1),
    })).status).toBe(413);
    const streamId = pairId();
    const streamPath = `/v1/pairs/${id}/blobs/${streamId}`;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(limit));
        controller.enqueue(new Uint8Array(1));
        controller.close();
      },
    });
    expect((await request(streamPath, { method: "PUT", headers: AUTH, body: stream })).status).toBe(413);
    expect((await request(streamPath, { method: "PUT", headers: AUTH, body: new Uint8Array([9]) })).status).toBe(201);
  }, 30000);

  it("unpairs by deleting state and closing its sockets", async () => {
    const id = pairId();
    await register(id);
    const socket = await connectedSocket(id, "laptop");
    await nextFrame(socket);
    expect((await request(`/v1/pairs/${id}`, { method: "DELETE" })).status).toBe(401);
    expect((await request(`/v1/pairs/${id}`, { method: "DELETE", headers: AUTH })).status).toBe(204);
    expect((await websocket(id, "laptop", TOKEN)).status).toBe(401);
    expect((await request(`/v1/pairs/${id}/blobs/${pairId()}`, { method: "GET", headers: AUTH })).status).toBe(401);
  });

  it("expires messages and blob chunks when the Durable Object alarm runs", async () => {
    const id = pairId();
    await register(id);
    const sender = await connectedSocket(id, "laptop");
    await nextFrame(sender);
    sender.send(JSON.stringify({ t: "send", id: uuid(), env: envelope() }));
    await nextFrame(sender);
    const blobId = pairId();
    await request(`/v1/pairs/${id}/blobs/${blobId}`, { method: "PUT", headers: AUTH, body: new Uint8Array([1, 2, 3]) });

    const doId = env.PAIRS.idFromName(id);
    const stub = env.PAIRS.get(doId);
    await runInDurableObject(stub, async (_instance, state) => {
      state.storage.sql.exec("UPDATE messages SET ts = ?", Date.now() - 8 * 24 * 60 * 60 * 1000);
      state.storage.sql.exec("UPDATE blobs SET created_at = ?", Date.now() - 25 * 60 * 60 * 1000);
      await state.storage.setAlarm(Date.now() - 1);
    });
    await runDurableObjectAlarm(stub);

    expect((await request(`/v1/pairs/${id}/blobs/${blobId}`, { method: "GET", headers: AUTH })).status).toBe(404);
    await runInDurableObject(stub, async (_instance, state) => {
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM messages").toArray()[0].n).toBe(0);
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM blob_chunks").toArray()[0].n).toBe(0);
    });
  });
});

describe("relay WebSocket delivery", () => {
  it("delivers stored, msg, ack and delivered frames between both roles with presence", async () => {
    const id = pairId();
    await register(id);
    const laptop = await connectedSocket(id, "laptop");
    expect(await nextFrame(laptop)).toEqual({ t: "hello", protocol: 1, peerOnline: false });
    laptop.send('{"t":"ping"}');
    expect(await nextFrame(laptop)).toEqual({ t: "pong" });
    laptop.send("{");
    expect(await nextFrame(laptop)).toMatchObject({ t: "error", code: "bad_frame" });
    const phone = await connectedSocket(id, "phone");
    expect(await nextFrame(phone)).toEqual({ t: "hello", protocol: 1, peerOnline: true });
    expect(await nextFrame(laptop)).toEqual({ t: "presence", peerOnline: true });

    const messageId = uuid();
    const envFrame = envelope();
    phone.send(JSON.stringify({ t: "send", id: messageId, env: envFrame }));
    expect(await nextFrame(phone)).toEqual({ t: "stored", id: messageId });
    expect(await nextFrame(laptop)).toMatchObject({ t: "msg", id: messageId, from: "phone", env: envFrame });
    laptop.send(JSON.stringify({ t: "ack", id: messageId }));
    expect(await nextFrame(phone)).toEqual({ t: "delivered", id: messageId });

    phone.close();
    expect(await nextFrame(laptop)).toEqual({ t: "presence", peerOnline: false });
  });

  it("flushes the offline queue in insertion order", async () => {
    const id = pairId();
    await register(id);
    const laptop = await connectedSocket(id, "laptop");
    await nextFrame(laptop);
    const ids = [uuid(), uuid()];
    for (const messageId of ids) {
      laptop.send(JSON.stringify({ t: "send", id: messageId, env: envelope() }));
      expect(await nextFrame(laptop)).toEqual({ t: "stored", id: messageId });
    }
    const phone = await connectedSocket(id, "phone");
    expect(await nextFrame(phone)).toMatchObject({ t: "hello", peerOnline: true });
    expect((await nextFrame(phone)).id).toBe(ids[0]);
    expect((await nextFrame(phone)).id).toBe(ids[1]);
  });

  it("reports duplicate ids, oversized frames and a full recipient queue", async () => {
    const id = pairId();
    await register(id);
    const socket = await connectedSocket(id, "phone");
    await nextFrame(socket);
    const repeatedId = uuid();
    const sent = JSON.stringify({ t: "send", id: repeatedId, env: envelope() });
    socket.send(sent);
    expect(await nextFrame(socket)).toMatchObject({ t: "stored", id: repeatedId });
    socket.send(sent);
    expect(await nextFrame(socket)).toMatchObject({ t: "error", id: repeatedId, code: "duplicate_id" });
    socket.send("x".repeat(64 * 1024 + 1));
    expect(await nextFrame(socket)).toMatchObject({ t: "error", code: "too_large" });

    const otherId = pairId();
    await register(otherId);
    const queued = await connectedSocket(otherId, "phone");
    await nextFrame(queued);
    for (let index = 0; index < 2; index++) {
      queued.send(JSON.stringify({ t: "send", id: uuid(), env: envelope() }));
      expect((await nextFrame(queued)).t).toBe("stored");
    }
    queued.send(JSON.stringify({ t: "send", id: uuid(), env: envelope() }));
    expect(await nextFrame(queued)).toMatchObject({ t: "error", code: "queue_full" });
  });
});

async function register(id: string): Promise<Response> {
  return request(`/v1/pairs/${id}/register`, { method: "POST", headers: AUTH });
}

async function request(path: string, init: RequestInit): Promise<Response> {
  return SELF.fetch(new Request(`https://tapdrop.test${path}`, init));
}

async function websocket(id: string, role: string, token: string): Promise<Response> {
  return SELF.fetch(
    new Request(`https://tapdrop.test/v1/pairs/${id}/ws?role=${role}`, {
      headers: { Authorization: `Bearer ${token}`, Upgrade: "websocket" },
    }),
  );
}

async function connectedSocket(id: string, role: "laptop" | "phone"): Promise<WebSocket> {
  const response = await websocket(id, role, TOKEN);
  expect(response.status).toBe(101);
  const socket = (response as Response & { webSocket: WebSocket }).webSocket;
  socket.accept();
  const queue: FrameQueue = { frames: [], waiters: [] };
  frameQueues.set(socket, queue);
  socket.addEventListener("message", (event) => {
    const frame = JSON.parse(String(event.data));
    const waiter = queue.waiters.shift();
    if (waiter) {
      clearTimeout(waiter.timer);
      waiter.resolve(frame);
    } else {
      queue.frames.push(frame);
    }
  });
  socket.addEventListener("error", () => {
    for (const waiter of queue.waiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("WebSocket failed"));
    }
  });
  openSockets.push(socket);
  return socket;
}

function nextFrame(socket: WebSocket): Promise<Record<string, any>> {
  const caller = new Error().stack?.split("\n")[2]?.trim() ?? "unknown caller";
  const queue = frameQueues.get(socket);
  if (!queue) throw new Error("WebSocket frame queue was not initialized");
  if (queue.frames.length) return Promise.resolve(queue.frames.shift()!);
  return new Promise((resolve, reject) => {
    const waiter: FrameWaiter = {
      resolve,
      reject,
      timer: setTimeout(() => {
        queue.waiters.splice(queue.waiters.indexOf(waiter), 1);
        reject(new Error(`Timed out waiting for WebSocket frame at ${caller}`));
      }, 3000),
    };
    queue.waiters.push(waiter);
  });
}

function pairId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function uuid(): string {
  return crypto.randomUUID();
}

function envelope(): { v: number; n: string; c: string } {
  return { v: 1, n: "AAAAAAAAAAAAAAAA", c: "AAAAAAAAAAAAAAAAAAAAAA" };
}
