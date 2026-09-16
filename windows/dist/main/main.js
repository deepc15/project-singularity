"use strict";

// src/main/main.ts
var import_promises = require("node:fs/promises");
var import_node_path = require("node:path");
var import_electron = require("electron");

// src/main/server.ts
var import_node_events = require("node:events");
var import_node_net = require("node:net");
var import_node_dgram = require("node:dgram");
var import_node_os = require("node:os");
var import_node_crypto = require("node:crypto");

// src/main/protocol.ts
var PROTO_VERSION = 1;
var TCP_PORT = 8787;
var UDP_PORT = 8788;
var DISCOVERY_PROBE = "SINGULAR_PROBE/1";
var MAX_PAYLOAD = 8 * 1024 * 1024;
var FrameType = {
  CONTROL: 1,
  VIDEO_CONFIG: 2,
  VIDEO_FRAME: 3,
  AUDIO_CONFIG: 4,
  AUDIO_FRAME: 5
};
var FRAME_FLAG_KEYFRAME = 1;
function encodeFrame(type, payload) {
  const header = Buffer.allocUnsafe(5);
  header.writeUInt8(type, 0);
  header.writeUInt32BE(payload.length, 1);
  return Buffer.concat([header, payload]);
}
function encodeControl(msg) {
  return encodeFrame(FrameType.CONTROL, Buffer.from(JSON.stringify(msg), "utf8"));
}
var FrameParser = class {
  constructor(onFrame, onError) {
    this.onFrame = onFrame;
    this.onError = onError;
  }
  pending = Buffer.alloc(0);
  push(chunk) {
    this.pending = this.pending.length === 0 ? chunk : Buffer.concat([this.pending, chunk]);
    for (; ; ) {
      if (this.pending.length < 5) return;
      const type = this.pending.readUInt8(0);
      const length = this.pending.readUInt32BE(1);
      if (length > MAX_PAYLOAD) {
        this.onError(new Error(`frame payload ${length} exceeds ${MAX_PAYLOAD}`));
        return;
      }
      if (this.pending.length < 5 + length) return;
      const payload = Buffer.from(this.pending.subarray(5, 5 + length));
      this.pending = this.pending.subarray(5 + length);
      this.onFrame({ type, payload });
    }
  }
};
function decodeVideoConfig(payload) {
  if (payload.length < 4) return null;
  return { streamId: payload.readUInt32BE(0), csd: Buffer.from(payload.subarray(4)) };
}
function decodeVideoFrame(payload) {
  if (payload.length < 13) return null;
  return {
    streamId: payload.readUInt32BE(0),
    keyframe: (payload.readUInt8(4) & FRAME_FLAG_KEYFRAME) !== 0,
    // Presentation timestamps stay well inside Number.MAX_SAFE_INTEGER.
    ptsUs: Number(payload.readBigUInt64BE(5)),
    data: Buffer.from(payload.subarray(13))
  };
}

// src/main/server.ts
var PING_INTERVAL_MS = 3e3;
var PING_TIMEOUT_MS = 9e3;
var SingularServer = class extends import_node_events.EventEmitter {
  tcp = null;
  udp = null;
  peers = /* @__PURE__ */ new Map();
  instanceId = (0, import_node_crypto.randomUUID)();
  /** Preferred virtual-display geometry advertised to the phone on connect. */
  tileGeometry = { w: 1280, h: 800, dpi: 200 };
  get pcName() {
    return (0, import_node_os.hostname)();
  }
  async start() {
    await this.startTcp();
    await this.startDiscovery();
  }
  stop() {
    for (const [, socket] of this.peers) socket.destroy();
    this.peers.clear();
    this.tcp?.close();
    this.udp?.close();
    this.tcp = null;
    this.udp = null;
  }
  startTcp() {
    return new Promise((resolve, reject) => {
      const server2 = (0, import_node_net.createServer)((socket) => this.onConnection(socket));
      server2.once("error", reject);
      server2.listen(TCP_PORT, "0.0.0.0", () => {
        server2.off("error", reject);
        server2.on("error", (err) => this.emit("log", `tcp error: ${err.message}`));
        this.emit("log", `listening on tcp/${TCP_PORT}`);
        this.tcp = server2;
        resolve();
      });
    });
  }
  startDiscovery() {
    return new Promise((resolve, reject) => {
      const sock = (0, import_node_dgram.createSocket)({ type: "udp4", reuseAddr: true });
      sock.once("error", reject);
      sock.on("message", (msg, rinfo) => {
        if (msg.toString("utf8").trim() !== DISCOVERY_PROBE) return;
        const reply = Buffer.from(
          JSON.stringify({
            t: "singular.pc",
            proto: PROTO_VERSION,
            name: this.pcName,
            port: TCP_PORT,
            id: this.instanceId
          }),
          "utf8"
        );
        sock.send(reply, rinfo.port, rinfo.address, (err) => {
          if (err) this.emit("log", `discovery reply failed: ${err.message}`);
        });
      });
      sock.bind(UDP_PORT, () => {
        sock.off("error", reject);
        sock.on("error", (err) => this.emit("log", `udp error: ${err.message}`));
        sock.setBroadcast(true);
        this.emit("log", `discovery responder on udp/${UDP_PORT}`);
        this.udp = sock;
        resolve();
      });
    });
  }
  onConnection(socket) {
    const id = (0, import_node_crypto.randomUUID)();
    const address = `${socket.remoteAddress ?? "?"}:${socket.remotePort ?? 0}`;
    socket.setNoDelay(true);
    this.peers.set(id, socket);
    let lastPong = Date.now();
    const handle = {
      id,
      address,
      send: (msg) => {
        if (!socket.destroyed) socket.write(encodeControl(msg));
      },
      close: (reason) => {
        this.emit("log", `closing ${address}: ${reason}`);
        socket.destroy();
      }
    };
    const parser = new FrameParser(
      (frame) => {
        switch (frame.type) {
          case FrameType.CONTROL: {
            let msg;
            try {
              msg = JSON.parse(frame.payload.toString("utf8"));
            } catch {
              this.emit("log", `bad control json from ${address}`);
              return;
            }
            if (msg.t === "pong") {
              lastPong = Date.now();
              return;
            }
            this.emit("control", handle, msg);
            return;
          }
          case FrameType.VIDEO_CONFIG: {
            const cfg = decodeVideoConfig(frame.payload);
            if (cfg) this.emit("video-config", handle, cfg);
            return;
          }
          case FrameType.VIDEO_FRAME: {
            const f = decodeVideoFrame(frame.payload);
            if (f) this.emit("video-frame", handle, f);
            return;
          }
          default:
            return;
        }
      },
      (err) => {
        this.emit("log", `framing error from ${address}: ${err.message}`);
        socket.destroy();
      }
    );
    const ping = setInterval(() => {
      if (Date.now() - lastPong > PING_TIMEOUT_MS) {
        handle.close("ping timeout");
        return;
      }
      handle.send({ t: "ping", ts: Date.now() });
    }, PING_INTERVAL_MS);
    socket.on("data", (chunk) => parser.push(chunk));
    socket.on("error", (err) => this.emit("log", `socket error ${address}: ${err.message}`));
    socket.on("close", () => {
      clearInterval(ping);
      this.peers.delete(id);
      this.emit("peer-gone", handle, "closed");
    });
    this.emit("log", `peer connected: ${address}`);
    this.emit("peer", handle);
    handle.send({
      t: "hello.ack",
      proto: PROTO_VERSION,
      name: this.pcName,
      tile: this.tileGeometry
    });
  }
};

// src/main/main.ts
var isDev = process.argv.includes("--dev");
var SCHEME = "app";
var HOST = "bundle";
import_electron.protocol.registerSchemesAsPrivileged([
  {
    scheme: SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true }
  }
]);
var MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".woff2": "font/woff2"
};
function registerBundleProtocol() {
  const rendererRoot = (0, import_node_path.join)(__dirname, "../renderer");
  import_electron.protocol.handle(SCHEME, async (request) => {
    const url = new URL(request.url);
    const requested = url.pathname === "/" ? "/index.html" : url.pathname;
    const target = (0, import_node_path.normalize)((0, import_node_path.join)(rendererRoot, decodeURIComponent(requested)));
    const rel = (0, import_node_path.relative)(rendererRoot, target);
    if (rel.startsWith("..") || (0, import_node_path.isAbsolute)(rel)) {
      return new Response("forbidden", { status: 403 });
    }
    try {
      const body = await (0, import_promises.readFile)(target);
      return new Response(body, {
        status: 200,
        headers: { "content-type": MIME[(0, import_node_path.extname)(target).toLowerCase()] ?? "application/octet-stream" }
      });
    } catch (err) {
      console.error(`[singular] ${request.url}: ${err.message}`);
      return new Response("not found", { status: 404 });
    }
  });
}
var win = null;
var server = null;
var peer = null;
function send(channel, ...args) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}
function createWindow() {
  win = new import_electron.BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 720,
    minHeight: 480,
    backgroundColor: "#0d1014",
    title: "Singular Desk",
    autoHideMenuBar: true,
    webPreferences: {
      preload: (0, import_node_path.join)(__dirname, "../preload/preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false
    }
  });
  win.loadURL(`${SCHEME}://${HOST}/index.html`);
  if (isDev) win.webContents.openDevTools({ mode: "detach" });
  win.webContents.setWindowOpenHandler(({ url }) => {
    void import_electron.shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("did-finish-load", () => {
    console.log("[singular] ui loaded");
  });
  win.webContents.on("did-fail-load", (_e, code, description, url) => {
    console.error(`[singular] load failed ${code} ${description} ${url}`);
  });
  win.webContents.on("console-message", (_e, level, message, line, source) => {
    console.log(`[console:${level}] ${message} (${source}:${line})`);
  });
  win.on("closed", () => {
    win = null;
  });
}
function wireServer() {
  const srv = new SingularServer();
  server = srv;
  srv.on("log", (line) => {
    console.log("[singular]", line);
    send("desk:log", line);
  });
  srv.on("peer", (handle) => {
    if (peer && peer.id !== handle.id) peer.close("superseded by a new connection");
    peer = handle;
    send("desk:peer", { id: handle.id, address: handle.address, connected: true });
  });
  srv.on("peer-gone", (handle, reason) => {
    if (peer?.id !== handle.id) return;
    peer = null;
    send("desk:peer", { id: handle.id, address: handle.address, connected: false, reason });
  });
  srv.on("control", (handle, msg) => {
    if (peer?.id !== handle.id) return;
    send("desk:control", msg);
  });
  srv.on("video-config", (handle, cfg) => {
    if (peer?.id !== handle.id) return;
    send("desk:video-config", { streamId: cfg.streamId, csd: cfg.csd });
  });
  srv.on("video-frame", (handle, f) => {
    if (peer?.id !== handle.id) return;
    send("desk:video-frame", {
      streamId: f.streamId,
      keyframe: f.keyframe,
      ptsUs: f.ptsUs,
      data: f.data
    });
  });
  srv.start().catch((err) => {
    console.error("[singular] failed to start server", err);
    send("desk:fatal", `Could not start the Singular listener: ${err.message}`);
  });
}
import_electron.ipcMain.on("desk:send", (_event, msg) => {
  peer?.send(msg);
});
import_electron.ipcMain.on("desk:report", (_event, line) => {
  console.log("[renderer]", line);
});
import_electron.ipcMain.handle("desk:set-tile-geometry", (_event, geom) => {
  if (server) server.tileGeometry = geom;
});
import_electron.ipcMain.handle("desk:info", () => ({
  pcName: server?.pcName ?? "",
  connected: peer !== null,
  version: import_electron.app.getVersion()
}));
import_electron.app.whenReady().then(() => {
  registerBundleProtocol();
  createWindow();
  wireServer();
  import_electron.app.on("activate", () => {
    if (import_electron.BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});
import_electron.app.on("window-all-closed", () => {
  server?.stop();
  import_electron.app.quit();
});
//# sourceMappingURL=main.js.map
