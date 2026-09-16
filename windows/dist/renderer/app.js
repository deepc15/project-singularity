"use strict";
(() => {
  // src/renderer/decoder.ts
  function* nalUnits(buf) {
    let i = 0;
    let start = -1;
    while (i + 2 < buf.length) {
      if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
        if (start >= 0) yield buf.subarray(start, i);
        i += 3;
        start = i;
      } else if (i + 3 < buf.length && buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 0 && buf[i + 3] === 1) {
        if (start >= 0) yield buf.subarray(start, i);
        i += 4;
        start = i;
      } else {
        i++;
      }
    }
    if (start >= 0 && start < buf.length) yield buf.subarray(start);
  }
  function codecStringFromCsd(csd) {
    for (const nal of nalUnits(csd)) {
      const nalType = (nal[0] ?? 0) & 31;
      if (nalType !== 7 || nal.length < 4) continue;
      const profile = nal[1];
      const constraints = nal[2];
      const level = nal[3];
      const hex = (n) => n.toString(16).padStart(2, "0");
      return `avc1.${hex(profile)}${hex(constraints)}${hex(level)}`;
    }
    return "avc1.42e01e";
  }
  var H264Decoder = class {
    constructor(cb) {
      this.cb = cb;
    }
    decoder = null;
    csd = null;
    codec = "avc1.42e01e";
    sawKeyframe = false;
    closed = false;
    /** Frames dropped because the decoder queue is already saturated. */
    droppedFrames = 0;
    decodedFrames = 0;
    configure(csd) {
      this.csd = csd;
      this.codec = codecStringFromCsd(csd);
      this.reset();
    }
    reset() {
      if (this.closed) return;
      this.teardown();
      this.sawKeyframe = false;
      const decoder = new VideoDecoder({
        output: (frame) => {
          this.decodedFrames++;
          if (this.closed) {
            frame.close();
            return;
          }
          this.cb.onFrame(frame);
        },
        error: (err) => {
          if (this.closed) return;
          this.cb.onError(err.message);
          this.reset();
          this.cb.onNeedKeyframe();
        }
      });
      decoder.configure({ codec: this.codec, optimizeForLatency: true });
      this.decoder = decoder;
    }
    decode(data, keyframe, ptsUs) {
      const decoder = this.decoder;
      if (!decoder || decoder.state !== "configured") return;
      if (!this.sawKeyframe) {
        if (!keyframe) return;
        this.sawKeyframe = true;
      }
      if (decoder.decodeQueueSize > 4 && !keyframe) {
        this.droppedFrames++;
        return;
      }
      const payload = keyframe && this.csd ? (() => {
        const merged = new Uint8Array(this.csd.length + data.length);
        merged.set(this.csd, 0);
        merged.set(data, this.csd.length);
        return merged;
      })() : data;
      try {
        decoder.decode(
          new EncodedVideoChunk({
            type: keyframe ? "key" : "delta",
            timestamp: ptsUs,
            data: payload
          })
        );
      } catch (err) {
        this.cb.onError(err instanceof Error ? err.message : String(err));
        this.reset();
        this.cb.onNeedKeyframe();
      }
    }
    teardown() {
      const decoder = this.decoder;
      this.decoder = null;
      if (!decoder) return;
      try {
        if (decoder.state !== "closed") decoder.close();
      } catch {
      }
    }
    close() {
      this.closed = true;
      this.teardown();
    }
  };

  // src/renderer/input.ts
  var KEYCODES = {
    Backspace: 67,
    Tab: 61,
    Enter: 66,
    Escape: 111,
    " ": 62,
    ArrowLeft: 21,
    ArrowRight: 22,
    ArrowUp: 19,
    ArrowDown: 20,
    Home: 122,
    End: 123,
    PageUp: 92,
    PageDown: 93,
    Delete: 112,
    Insert: 124,
    F1: 131,
    F2: 132,
    F3: 133,
    F4: 134,
    F5: 135,
    F6: 136,
    F7: 137,
    F8: 138,
    F9: 139,
    F10: 140,
    F11: 141,
    F12: 142
  };
  var META = {
    SHIFT_ON: 1,
    ALT_ON: 2,
    SYM_ON: 4,
    CTRL_ON: 4096,
    META_ON: 65536
  };
  function metaStateOf(e) {
    let meta = 0;
    if (e.shiftKey) meta |= META.SHIFT_ON;
    if (e.altKey) meta |= META.ALT_ON;
    if (e.ctrlKey) meta |= META.CTRL_ON;
    if (e.metaKey) meta |= META.META_ON;
    return meta;
  }
  function translateKey(e) {
    const mapped = KEYCODES[e.key];
    if (mapped !== void 0) return { kind: "key", keyCode: mapped, meta: metaStateOf(e) };
    if ([...e.key].length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
      return { kind: "text", text: e.key };
    }
    return null;
  }
  var SYSTEM_KEYS = {
    back: 4,
    home: 3,
    recents: 187,
    volumeUp: 24,
    volumeDown: 25
  };
  function normalizePointer(clientX, clientY, videoRect) {
    if (videoRect.width <= 0 || videoRect.height <= 0) return null;
    const x = (clientX - videoRect.left) / videoRect.width;
    const y = (clientY - videoRect.top) / videoRect.height;
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    return { x, y };
  }
  function clampPointer(clientX, clientY, videoRect) {
    const x = (clientX - videoRect.left) / Math.max(videoRect.width, 1);
    const y = (clientY - videoRect.top) / Math.max(videoRect.height, 1);
    return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) };
  }
  function fitRect(boxW, boxH, srcW, srcH) {
    if (srcW <= 0 || srcH <= 0) return { w: boxW, h: boxH, left: 0, top: 0 };
    const scale = Math.min(boxW / srcW, boxH / srcH);
    const w = Math.max(1, Math.floor(srcW * scale));
    const h = Math.max(1, Math.floor(srcH * scale));
    return { w, h, left: Math.floor((boxW - w) / 2), top: Math.floor((boxH - h) / 2) };
  }

  // src/renderer/tile.ts
  var GEOMETRY_DEBOUNCE_MS = 400;
  var GEOMETRY_MIN_DELTA_PX = 48;
  var Tile = class {
    id;
    pkg;
    label;
    mode;
    root;
    stage;
    canvas;
    ctx;
    statsEl;
    badgeEl;
    decoder;
    send;
    onClosed;
    resizeObserver;
    srcW;
    srcH;
    dpi;
    pointerDown = false;
    geometryTimer = null;
    lastRequestedGeometry = null;
    frameCount = 0;
    lastStatsAt = performance.now();
    pendingFrame = null;
    rafHandle = 0;
    disposed = false;
    constructor(init) {
      this.id = init.id;
      this.pkg = init.pkg;
      this.label = init.label;
      this.mode = init.mode;
      this.srcW = init.width;
      this.srcH = init.height;
      this.dpi = init.dpi;
      this.send = init.send;
      this.onClosed = init.onClosed;
      this.root = document.createElement("section");
      this.root.className = "tile";
      this.root.tabIndex = 0;
      this.root.dataset.streamId = String(init.id);
      this.root.innerHTML = `
      <header class="tile-bar">
        <img class="tile-icon" alt="" />
        <div class="tile-titles">
          <span class="tile-label"></span>
          <span class="tile-pkg"></span>
        </div>
        <span class="tile-badge"></span>
        <span class="tile-stats"></span>
        <div class="tile-actions">
          <button class="btn icon" data-act="back"    title="Back">\u2039</button>
          <button class="btn icon" data-act="home"    title="Home">\u25CB</button>
          <button class="btn icon" data-act="recents" title="Recents">\u25AD</button>
          <button class="btn icon" data-act="expand"  title="Focus this app">\u2922</button>
          <button class="btn recall" data-act="recall">Bring back to phone</button>
        </div>
      </header>
      <div class="tile-stage"><canvas></canvas></div>
    `;
      const icon = this.root.querySelector(".tile-icon");
      if (init.iconPng) icon.src = `data:image/png;base64,${init.iconPng}`;
      else icon.remove();
      this.root.querySelector(".tile-label").textContent = init.label;
      this.root.querySelector(".tile-pkg").textContent = init.pkg;
      this.badgeEl = this.root.querySelector(".tile-badge");
      this.badgeEl.textContent = init.mode === "display" ? "own display" : "screen mirror";
      this.badgeEl.classList.add(init.mode === "display" ? "ok" : "warn");
      this.statsEl = this.root.querySelector(".tile-stats");
      this.stage = this.root.querySelector(".tile-stage");
      this.canvas = this.root.querySelector("canvas");
      const ctx = this.canvas.getContext("2d", { alpha: false, desynchronized: true });
      if (!ctx) throw new Error("2D canvas context unavailable");
      this.ctx = ctx;
      this.decoder = new H264Decoder({
        onFrame: (frame) => this.queueFrame(frame),
        onNeedKeyframe: () => this.send({ t: "stream.keyframe", id: this.id }),
        onError: (msg) => console.warn(`[tile ${this.id}] decode: ${msg}`)
      });
      this.wireActions();
      this.wirePointer();
      this.wireKeyboard();
      this.resizeObserver = new ResizeObserver(() => this.layout());
      this.resizeObserver.observe(this.stage);
      this.layout();
    }
    // -------------------------------------------------------------------------
    // Media
    // -------------------------------------------------------------------------
    configure(csd) {
      this.decoder.configure(csd);
    }
    pushFrame(data, keyframe, ptsUs) {
      this.decoder.decode(data, keyframe, ptsUs);
    }
    /**
     * Hold at most one decoded frame and paint on the next vsync. Painting every
     * decoder output would burn GPU time on frames the user never sees.
     */
    queueFrame(frame) {
      if (this.disposed) {
        frame.close();
        return;
      }
      this.pendingFrame?.close();
      this.pendingFrame = frame;
      if (this.rafHandle === 0) {
        this.rafHandle = requestAnimationFrame(() => {
          this.rafHandle = 0;
          this.paint();
        });
      }
    }
    paint() {
      const frame = this.pendingFrame;
      this.pendingFrame = null;
      if (!frame) return;
      if (frame.displayWidth !== this.srcW || frame.displayHeight !== this.srcH) {
        this.srcW = frame.displayWidth;
        this.srcH = frame.displayHeight;
        this.layout();
      }
      if (this.canvas.width !== this.srcW || this.canvas.height !== this.srcH) {
        this.canvas.width = this.srcW;
        this.canvas.height = this.srcH;
      }
      this.ctx.drawImage(frame, 0, 0);
      frame.close();
      this.frameCount++;
      const now = performance.now();
      if (now - this.lastStatsAt >= 1e3) {
        const fps = this.frameCount * 1e3 / (now - this.lastStatsAt);
        this.statsEl.textContent = `${this.srcW}\xD7${this.srcH} \xB7 ${fps.toFixed(0)} fps`;
        this.frameCount = 0;
        this.lastStatsAt = now;
      }
    }
    /** Called when the phone reports the virtual display changed size. */
    resized(w, h, dpi) {
      this.srcW = w;
      this.srcH = h;
      this.dpi = dpi;
      this.layout();
    }
    // -------------------------------------------------------------------------
    // Layout — "automatically fit into the pc client screen"
    // -------------------------------------------------------------------------
    layout() {
      const box = this.stage.getBoundingClientRect();
      const fit = fitRect(box.width, box.height, this.srcW, this.srcH);
      this.canvas.style.width = `${fit.w}px`;
      this.canvas.style.height = `${fit.h}px`;
      this.canvas.style.left = `${fit.left}px`;
      this.canvas.style.top = `${fit.top}px`;
      this.scheduleGeometryUpdate(box.width, box.height);
    }
    /**
     * Re-cut the Android virtual display to the tile's real pixel size so the app
     * renders at native resolution and lays itself out for this aspect ratio,
     * rather than being scaled from the phone's shape. Mirror mode has no virtual
     * display to resize.
     */
    scheduleGeometryUpdate(boxW, boxH) {
      if (this.mode !== "display") return;
      const w = Math.max(320, Math.round(boxW / 2) * 2);
      const h = Math.max(320, Math.round(boxH / 2) * 2);
      const last = this.lastRequestedGeometry;
      if (last && Math.abs(last.w - w) < GEOMETRY_MIN_DELTA_PX && Math.abs(last.h - h) < GEOMETRY_MIN_DELTA_PX) {
        return;
      }
      if (this.geometryTimer !== null) clearTimeout(this.geometryTimer);
      this.geometryTimer = window.setTimeout(() => {
        this.geometryTimer = null;
        this.lastRequestedGeometry = { w, h };
        this.send({ t: "stream.geometry", id: this.id, w, h, dpi: this.dpi });
      }, GEOMETRY_DEBOUNCE_MS);
    }
    videoRect() {
      return this.canvas.getBoundingClientRect();
    }
    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------
    wireActions() {
      this.root.querySelector(".tile-actions").addEventListener("click", (ev) => {
        const target = ev.target.closest("[data-act]");
        if (!target) return;
        ev.stopPropagation();
        switch (target.dataset.act) {
          case "recall":
            this.send({ t: "stream.recall", id: this.id });
            break;
          case "expand":
            this.root.classList.toggle("focused");
            this.root.dispatchEvent(
              new CustomEvent("tile-focus-toggle", { bubbles: true, detail: this.id })
            );
            break;
          case "back":
            this.tapSystemKey(SYSTEM_KEYS.back);
            break;
          case "home":
            this.tapSystemKey(SYSTEM_KEYS.home);
            break;
          case "recents":
            this.tapSystemKey(SYSTEM_KEYS.recents);
            break;
        }
      });
    }
    tapSystemKey(keyCode) {
      this.send({ t: "input.key", id: this.id, action: "down", keyCode, meta: 0 });
      this.send({ t: "input.key", id: this.id, action: "up", keyCode, meta: 0 });
    }
    wirePointer() {
      this.canvas.addEventListener("pointerdown", (e) => {
        const p = normalizePointer(e.clientX, e.clientY, this.videoRect());
        if (!p) return;
        this.canvas.setPointerCapture(e.pointerId);
        this.pointerDown = true;
        this.root.focus();
        this.send({ t: "input.touch", id: this.id, action: "down", x: p.x, y: p.y, pointer: 0 });
        e.preventDefault();
      });
      this.canvas.addEventListener("pointermove", (e) => {
        if (!this.pointerDown) return;
        const p = clampPointer(e.clientX, e.clientY, this.videoRect());
        this.send({ t: "input.touch", id: this.id, action: "move", x: p.x, y: p.y, pointer: 0 });
      });
      const release = (e, action) => {
        if (!this.pointerDown) return;
        this.pointerDown = false;
        const p = clampPointer(e.clientX, e.clientY, this.videoRect());
        this.send({ t: "input.touch", id: this.id, action, x: p.x, y: p.y, pointer: 0 });
      };
      this.canvas.addEventListener("pointerup", (e) => release(e, "up"));
      this.canvas.addEventListener("pointercancel", (e) => release(e, "cancel"));
      this.canvas.addEventListener("contextmenu", (e) => {
        e.preventDefault();
        this.tapSystemKey(SYSTEM_KEYS.back);
      });
      this.canvas.addEventListener(
        "wheel",
        (e) => {
          const p = normalizePointer(e.clientX, e.clientY, this.videoRect());
          if (!p) return;
          e.preventDefault();
          const scale = e.deltaMode === 0 ? 1 / 100 : 1;
          this.send({
            t: "input.scroll",
            id: this.id,
            x: p.x,
            y: p.y,
            dx: -e.deltaX * scale,
            dy: -e.deltaY * scale
          });
        },
        { passive: false }
      );
    }
    wireKeyboard() {
      this.root.addEventListener("keydown", (e) => {
        const t = translateKey(e);
        if (!t) return;
        e.preventDefault();
        if (t.kind === "text") this.send({ t: "input.text", id: this.id, text: t.text });
        else
          this.send({
            t: "input.key",
            id: this.id,
            action: "down",
            keyCode: t.keyCode,
            meta: t.meta
          });
      });
      this.root.addEventListener("keyup", (e) => {
        const t = translateKey(e);
        if (!t || t.kind !== "key") return;
        e.preventDefault();
        this.send({
          t: "input.key",
          id: this.id,
          action: "up",
          keyCode: t.keyCode,
          meta: metaStateOf(e)
        });
      });
    }
    // -------------------------------------------------------------------------
    /** Flag that the phone asked the user for text input on this app. */
    setImeHint(active) {
      this.root.classList.toggle("ime", active);
    }
    dispose() {
      if (this.disposed) return;
      this.disposed = true;
      if (this.geometryTimer !== null) clearTimeout(this.geometryTimer);
      if (this.rafHandle !== 0) cancelAnimationFrame(this.rafHandle);
      this.pendingFrame?.close();
      this.pendingFrame = null;
      this.resizeObserver.disconnect();
      this.decoder.close();
      this.root.remove();
      this.onClosed(this.id);
    }
  };

  // src/renderer/app.ts
  var desk = window.desk;
  var send = (msg) => desk.send(msg);
  var el = (sel) => {
    const node = document.querySelector(sel);
    if (!node) throw new Error(`missing element: ${sel}`);
    return node;
  };
  var grid = el("#grid");
  var emptyState = el("#empty");
  var statusPill = el("#status");
  var deviceLine = el("#device");
  var capsLine = el("#caps");
  var drawer = el("#drawer");
  var appList = el("#app-list");
  var appFilter = el("#app-filter");
  var logPanel = el("#log");
  var toastHost = el("#toasts");
  var hostLine = el("#host");
  var tiles = /* @__PURE__ */ new Map();
  var inventory = [];
  var iconCache = /* @__PURE__ */ new Map();
  function toast(text, level = "info") {
    const node = document.createElement("div");
    node.className = `toast ${level}`;
    node.textContent = text;
    toastHost.append(node);
    setTimeout(() => node.classList.add("out"), 4200);
    setTimeout(() => node.remove(), 4800);
  }
  function log(line) {
    const node = document.createElement("div");
    node.className = "log-line";
    node.textContent = `${(/* @__PURE__ */ new Date()).toLocaleTimeString()}  ${line}`;
    logPanel.prepend(node);
    while (logPanel.childElementCount > 300) logPanel.lastElementChild?.remove();
    desk.report(line);
  }
  window.addEventListener(
    "error",
    (e) => desk.report(`uncaught: ${e.message} (${e.filename}:${e.lineno})`)
  );
  window.addEventListener(
    "unhandledrejection",
    (e) => desk.report(`unhandled rejection: ${String(e.reason)}`)
  );
  function refreshLayout() {
    const count = tiles.size;
    emptyState.hidden = count > 0;
    grid.hidden = count === 0;
    const focused = grid.querySelector(".tile.focused") !== null;
    const cols = focused ? 1 : Math.max(1, Math.ceil(Math.sqrt(count)));
    grid.style.setProperty("--cols", String(cols));
    grid.classList.toggle("has-focus", focused);
  }
  function openTile(msg) {
    tiles.get(msg.id)?.dispose();
    const tile = new Tile({
      id: msg.id,
      pkg: msg.app.pkg,
      label: msg.app.label,
      iconPng: iconCache.get(msg.app.pkg),
      width: msg.w,
      height: msg.h,
      dpi: msg.dpi,
      mode: msg.mode,
      send,
      onClosed: (id) => {
        tiles.delete(id);
        refreshLayout();
      }
    });
    tiles.set(msg.id, tile);
    grid.append(tile.root);
    refreshLayout();
    tile.root.focus();
    log(`stream ${msg.id} started: ${msg.app.label} (${msg.mode}, ${msg.w}\xD7${msg.h})`);
  }
  grid.addEventListener("tile-focus-toggle", (ev) => {
    const focusedId = ev.detail;
    for (const [id, tile] of tiles) {
      if (id !== focusedId) tile.root.classList.remove("focused");
    }
    refreshLayout();
  });
  function renderAppList() {
    const needle = appFilter.value.trim().toLowerCase();
    const visible = inventory.filter((a) => !needle || a.label.toLowerCase().includes(needle) || a.pkg.includes(needle)).sort((a, b) => {
      if (a.running !== b.running) return a.running ? -1 : 1;
      if (a.recent !== b.recent) return a.recent ? -1 : 1;
      return a.label.localeCompare(b.label);
    });
    appList.replaceChildren();
    if (inventory.length === 0) {
      const hint = document.createElement("p");
      hint.className = "muted pad";
      hint.textContent = "Connect a phone to see its apps.";
      appList.append(hint);
      return;
    }
    for (const entry of visible) {
      const row = document.createElement("button");
      row.className = "app-row";
      row.type = "button";
      row.title = entry.pkg;
      const icon = iconCache.get(entry.pkg);
      if (icon) {
        const img = document.createElement("img");
        img.src = `data:image/png;base64,${icon}`;
        img.alt = "";
        row.append(img);
      } else {
        const ph = document.createElement("span");
        ph.className = "app-icon-placeholder";
        ph.textContent = entry.label.slice(0, 1).toUpperCase();
        row.append(ph);
      }
      const name = document.createElement("span");
      name.className = "app-name";
      name.textContent = entry.label;
      row.append(name);
      if (entry.running || entry.recent) {
        const dot = document.createElement("span");
        dot.className = entry.running ? "dot running" : "dot recent";
        dot.title = entry.running ? "Running" : "Recently used";
        row.append(dot);
      }
      row.addEventListener("click", () => {
        send({ t: "stream.request", pkg: entry.pkg });
        toast(`Asking the phone to cast ${entry.label}\u2026`);
      });
      appList.append(row);
    }
  }
  appFilter.addEventListener("input", renderAppList);
  el("#refresh-apps").addEventListener("click", () => send({ t: "apps.refresh" }));
  el("#toggle-drawer").addEventListener("click", () => {
    drawer.classList.toggle("open");
  });
  el("#toggle-log").addEventListener("click", () => {
    logPanel.classList.toggle("open");
  });
  function setConnected(connected, address) {
    statusPill.textContent = connected ? `Connected \xB7 ${address ?? ""}` : "Waiting for a phone";
    statusPill.classList.toggle("on", connected);
    if (connected) return;
    for (const tile of [...tiles.values()]) tile.dispose();
    tiles.clear();
    inventory = [];
    deviceLine.textContent = "";
    capsLine.replaceChildren();
    renderAppList();
    refreshLayout();
  }
  function renderCaps(device, caps) {
    deviceLine.textContent = `${device.name} \xB7 ${device.model} \xB7 Android SDK ${device.sdk} \xB7 ${device.w}\xD7${device.h}`;
    capsLine.replaceChildren();
    const chips = [
      ["per-app displays", caps.privileged, "Shizuku/ADB is available, so each app gets its own display"],
      ["gesture input", caps.accessibility, "Accessibility service enabled \u2014 fallback input path"],
      ["screen mirror", caps.mirror, "MediaProjection whole-screen capture available"]
    ];
    for (const [label, on, title] of chips) {
      const chip = document.createElement("span");
      chip.className = `chip ${on ? "ok" : "off"}`;
      chip.textContent = label;
      chip.title = title;
      capsLine.append(chip);
    }
    if (!caps.privileged) {
      toast(
        "Phone has no Shizuku/ADB access \u2014 falling back to whole-screen mirroring, one tile only.",
        "warn"
      );
    }
  }
  desk.onPeer((status) => {
    setConnected(status.connected, status.address);
    log(status.connected ? `phone connected (${status.address})` : `phone gone: ${status.reason ?? ""}`);
  });
  desk.onControl((msg) => {
    switch (msg.t) {
      case "hello":
        renderCaps(msg.device, msg.caps);
        break;
      case "apps":
        for (const entry of msg.list) {
          if (entry.iconPng) iconCache.set(entry.pkg, entry.iconPng);
        }
        inventory = msg.list;
        renderAppList();
        break;
      case "stream.start":
        openTile(msg);
        break;
      case "stream.stop":
        tiles.get(msg.id)?.dispose();
        log(`stream ${msg.id} stopped (${msg.reason})`);
        break;
      case "stream.resized":
        tiles.get(msg.id)?.resized(msg.w, msg.h, msg.dpi);
        break;
      case "ime.show":
        tiles.get(msg.id)?.setImeHint(true);
        break;
      case "ime.hide":
        tiles.get(msg.id)?.setImeHint(false);
        break;
      case "toast":
        toast(msg.text, msg.level);
        log(`phone: ${msg.text}`);
        break;
    }
  });
  desk.onVideoConfig(({ streamId, csd }) => tiles.get(streamId)?.configure(csd));
  desk.onVideoFrame(
    ({ streamId, keyframe, ptsUs, data }) => tiles.get(streamId)?.pushFrame(data, keyframe, ptsUs)
  );
  desk.onLog(log);
  desk.onFatal((msg) => {
    toast(msg, "error");
    log(msg);
  });
  window.addEventListener("resize", refreshLayout);
  void desk.info().then((info) => {
    hostLine.textContent = `${info.pcName} \xB7 tcp 8787`;
    setConnected(info.connected);
  });
  setConnected(false);
  refreshLayout();
})();
//# sourceMappingURL=app.js.map
