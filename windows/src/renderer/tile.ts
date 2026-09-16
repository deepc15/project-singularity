import { H264Decoder } from './decoder';
import {
  SYSTEM_KEYS,
  clampPointer,
  fitRect,
  metaStateOf,
  normalizePointer,
  translateKey,
} from './input';
import type { CastMode, OutboundControl } from '../main/protocol';

export interface TileInit {
  id: number;
  pkg: string;
  label: string;
  iconPng?: string;
  width: number;
  height: number;
  dpi: number;
  mode: CastMode;
  send: (msg: OutboundControl) => void;
  onClosed: (id: number) => void;
}

/** Ask the phone to resize the virtual display at most this often. */
const GEOMETRY_DEBOUNCE_MS = 400;
/** Ignore sub-pixel-ish churn so a window drag doesn't thrash the encoder. */
const GEOMETRY_MIN_DELTA_PX = 48;

/**
 * One cast application on the receiving screen: a decoder, a canvas, and the
 * input plumbing that makes the app usable with the PC's mouse and keyboard.
 */
export class Tile {
  readonly id: number;
  readonly pkg: string;
  readonly label: string;
  readonly mode: CastMode;
  readonly root: HTMLElement;

  private readonly stage: HTMLElement;
  private readonly canvas: HTMLCanvasElement;
  private readonly ctx: CanvasRenderingContext2D;
  private readonly statsEl: HTMLElement;
  private readonly badgeEl: HTMLElement;
  private readonly decoder: H264Decoder;
  private readonly send: (msg: OutboundControl) => void;
  private readonly onClosed: (id: number) => void;
  private readonly resizeObserver: ResizeObserver;

  private srcW: number;
  private srcH: number;
  private dpi: number;
  private pointerDown = false;
  private geometryTimer: number | null = null;
  private lastRequestedGeometry: { w: number; h: number } | null = null;
  private frameCount = 0;
  private lastStatsAt = performance.now();
  private pendingFrame: VideoFrame | null = null;
  private rafHandle = 0;
  private disposed = false;

  constructor(init: TileInit) {
    this.id = init.id;
    this.pkg = init.pkg;
    this.label = init.label;
    this.mode = init.mode;
    this.srcW = init.width;
    this.srcH = init.height;
    this.dpi = init.dpi;
    this.send = init.send;
    this.onClosed = init.onClosed;

    this.root = document.createElement('section');
    this.root.className = 'tile';
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
          <button class="btn icon" data-act="back"    title="Back">‹</button>
          <button class="btn icon" data-act="home"    title="Home">○</button>
          <button class="btn icon" data-act="recents" title="Recents">▭</button>
          <button class="btn icon" data-act="expand"  title="Focus this app">⤢</button>
          <button class="btn recall" data-act="recall">Bring back to phone</button>
        </div>
      </header>
      <div class="tile-stage"><canvas></canvas></div>
    `;

    const icon = this.root.querySelector<HTMLImageElement>('.tile-icon')!;
    if (init.iconPng) icon.src = `data:image/png;base64,${init.iconPng}`;
    else icon.remove();

    this.root.querySelector('.tile-label')!.textContent = init.label;
    this.root.querySelector('.tile-pkg')!.textContent = init.pkg;

    this.badgeEl = this.root.querySelector<HTMLElement>('.tile-badge')!;
    this.badgeEl.textContent = init.mode === 'display' ? 'own display' : 'screen mirror';
    this.badgeEl.classList.add(init.mode === 'display' ? 'ok' : 'warn');

    this.statsEl = this.root.querySelector<HTMLElement>('.tile-stats')!;
    this.stage = this.root.querySelector<HTMLElement>('.tile-stage')!;
    this.canvas = this.root.querySelector<HTMLCanvasElement>('canvas')!;

    const ctx = this.canvas.getContext('2d', { alpha: false, desynchronized: true });
    if (!ctx) throw new Error('2D canvas context unavailable');
    this.ctx = ctx;

    this.decoder = new H264Decoder({
      onFrame: (frame) => this.queueFrame(frame),
      onNeedKeyframe: () => this.send({ t: 'stream.keyframe', id: this.id }),
      onError: (msg) => console.warn(`[tile ${this.id}] decode: ${msg}`),
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

  configure(csd: Uint8Array): void {
    this.decoder.configure(csd);
  }

  pushFrame(data: Uint8Array, keyframe: boolean, ptsUs: number): void {
    this.decoder.decode(data, keyframe, ptsUs);
  }

  /**
   * Hold at most one decoded frame and paint on the next vsync. Painting every
   * decoder output would burn GPU time on frames the user never sees.
   */
  private queueFrame(frame: VideoFrame): void {
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

  private paint(): void {
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
    if (now - this.lastStatsAt >= 1000) {
      const fps = (this.frameCount * 1000) / (now - this.lastStatsAt);
      this.statsEl.textContent = `${this.srcW}×${this.srcH} · ${fps.toFixed(0)} fps`;
      this.frameCount = 0;
      this.lastStatsAt = now;
    }
  }

  /** Called when the phone reports the virtual display changed size. */
  resized(w: number, h: number, dpi: number): void {
    this.srcW = w;
    this.srcH = h;
    this.dpi = dpi;
    this.layout();
  }

  // -------------------------------------------------------------------------
  // Layout — "automatically fit into the pc client screen"
  // -------------------------------------------------------------------------

  private layout(): void {
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
  private scheduleGeometryUpdate(boxW: number, boxH: number): void {
    if (this.mode !== 'display') return;
    // Even pixel dimensions keep H.264 chroma subsampling happy.
    const w = Math.max(320, Math.round(boxW / 2) * 2);
    const h = Math.max(320, Math.round(boxH / 2) * 2);
    const last = this.lastRequestedGeometry;
    if (
      last &&
      Math.abs(last.w - w) < GEOMETRY_MIN_DELTA_PX &&
      Math.abs(last.h - h) < GEOMETRY_MIN_DELTA_PX
    ) {
      return;
    }
    if (this.geometryTimer !== null) clearTimeout(this.geometryTimer);
    this.geometryTimer = window.setTimeout(() => {
      this.geometryTimer = null;
      this.lastRequestedGeometry = { w, h };
      this.send({ t: 'stream.geometry', id: this.id, w, h, dpi: this.dpi });
    }, GEOMETRY_DEBOUNCE_MS);
  }

  private videoRect(): DOMRect {
    return this.canvas.getBoundingClientRect();
  }

  // -------------------------------------------------------------------------
  // Input
  // -------------------------------------------------------------------------

  private wireActions(): void {
    this.root.querySelector('.tile-actions')!.addEventListener('click', (ev) => {
      const target = (ev.target as HTMLElement).closest<HTMLElement>('[data-act]');
      if (!target) return;
      ev.stopPropagation();
      switch (target.dataset.act) {
        case 'recall':
          this.send({ t: 'stream.recall', id: this.id });
          break;
        case 'expand':
          this.root.classList.toggle('focused');
          this.root.dispatchEvent(
            new CustomEvent('tile-focus-toggle', { bubbles: true, detail: this.id }),
          );
          break;
        case 'back':
          this.tapSystemKey(SYSTEM_KEYS.back);
          break;
        case 'home':
          this.tapSystemKey(SYSTEM_KEYS.home);
          break;
        case 'recents':
          this.tapSystemKey(SYSTEM_KEYS.recents);
          break;
      }
    });
  }

  private tapSystemKey(keyCode: number): void {
    this.send({ t: 'input.key', id: this.id, action: 'down', keyCode, meta: 0 });
    this.send({ t: 'input.key', id: this.id, action: 'up', keyCode, meta: 0 });
  }

  private wirePointer(): void {
    this.canvas.addEventListener('pointerdown', (e) => {
      const p = normalizePointer(e.clientX, e.clientY, this.videoRect());
      if (!p) return;
      this.canvas.setPointerCapture(e.pointerId);
      this.pointerDown = true;
      this.root.focus();
      this.send({ t: 'input.touch', id: this.id, action: 'down', x: p.x, y: p.y, pointer: 0 });
      e.preventDefault();
    });

    this.canvas.addEventListener('pointermove', (e) => {
      if (!this.pointerDown) return;
      const p = clampPointer(e.clientX, e.clientY, this.videoRect());
      this.send({ t: 'input.touch', id: this.id, action: 'move', x: p.x, y: p.y, pointer: 0 });
    });

    const release = (e: PointerEvent, action: 'up' | 'cancel') => {
      if (!this.pointerDown) return;
      this.pointerDown = false;
      const p = clampPointer(e.clientX, e.clientY, this.videoRect());
      this.send({ t: 'input.touch', id: this.id, action, x: p.x, y: p.y, pointer: 0 });
    };
    this.canvas.addEventListener('pointerup', (e) => release(e, 'up'));
    this.canvas.addEventListener('pointercancel', (e) => release(e, 'cancel'));

    // Right-click is the natural gesture for Android's Back.
    this.canvas.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      this.tapSystemKey(SYSTEM_KEYS.back);
    });

    this.canvas.addEventListener(
      'wheel',
      (e) => {
        const p = normalizePointer(e.clientX, e.clientY, this.videoRect());
        if (!p) return;
        e.preventDefault();
        // deltaMode 0 is pixels; normalise to notches the way Android expects.
        const scale = e.deltaMode === 0 ? 1 / 100 : 1;
        this.send({
          t: 'input.scroll',
          id: this.id,
          x: p.x,
          y: p.y,
          dx: -e.deltaX * scale,
          dy: -e.deltaY * scale,
        });
      },
      { passive: false },
    );
  }

  private wireKeyboard(): void {
    this.root.addEventListener('keydown', (e) => {
      const t = translateKey(e);
      if (!t) return;
      e.preventDefault();
      if (t.kind === 'text') this.send({ t: 'input.text', id: this.id, text: t.text });
      else
        this.send({
          t: 'input.key',
          id: this.id,
          action: 'down',
          keyCode: t.keyCode,
          meta: t.meta,
        });
    });

    this.root.addEventListener('keyup', (e) => {
      const t = translateKey(e);
      if (!t || t.kind !== 'key') return;
      e.preventDefault();
      this.send({
        t: 'input.key',
        id: this.id,
        action: 'up',
        keyCode: t.keyCode,
        meta: metaStateOf(e),
      });
    });
  }

  // -------------------------------------------------------------------------

  /** Flag that the phone asked the user for text input on this app. */
  setImeHint(active: boolean): void {
    this.root.classList.toggle('ime', active);
  }

  dispose(): void {
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
}
