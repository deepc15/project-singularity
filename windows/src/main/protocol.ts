/**
 * Singular Protocol v1 — framing and message types.
 * See ../../PROTOCOL.md for the normative description.
 */

export const PROTO_VERSION = 1;
export const TCP_PORT = 8787;
export const UDP_PORT = 8788;
export const DISCOVERY_PROBE = 'SINGULAR_PROBE/1';

/** Refuse anything larger — a bogus length prefix should not let a peer OOM us. */
export const MAX_PAYLOAD = 8 * 1024 * 1024;

export const FrameType = {
  CONTROL: 0x01,
  VIDEO_CONFIG: 0x02,
  VIDEO_FRAME: 0x03,
  AUDIO_CONFIG: 0x04,
  AUDIO_FRAME: 0x05,
} as const;
export type FrameType = (typeof FrameType)[keyof typeof FrameType];

export const FRAME_FLAG_KEYFRAME = 0x01;

export interface Frame {
  type: number;
  payload: Buffer;
}

// ---------------------------------------------------------------------------
// Control messages
// ---------------------------------------------------------------------------

export interface DeviceInfo {
  name: string;
  model: string;
  sdk: number;
  w: number;
  h: number;
  dpi: number;
}

export interface DeviceCaps {
  /** Shizuku/ADB available: real per-app virtual displays and event injection. */
  privileged: boolean;
  /** Accessibility service enabled: gesture-based fallback input. */
  accessibility: boolean;
  /** MediaProjection whole-screen mirroring available. */
  mirror: boolean;
}

export interface AppEntry {
  pkg: string;
  label: string;
  running: boolean;
  recent: boolean;
  /** base64 PNG, sent once per package. */
  iconPng?: string;
}

export type CastMode = 'display' | 'mirror';

export type InboundControl =
  | { t: 'hello'; proto: number; device: DeviceInfo; caps: DeviceCaps }
  | { t: 'apps'; list: AppEntry[] }
  | {
      t: 'stream.start';
      id: number;
      app: { pkg: string; label: string };
      w: number;
      h: number;
      dpi: number;
      mode: CastMode;
    }
  | { t: 'stream.stop'; id: number; reason: string }
  | { t: 'stream.resized'; id: number; w: number; h: number; dpi: number }
  | { t: 'ime.show'; id: number }
  | { t: 'ime.hide'; id: number }
  | { t: 'toast'; text: string; level: 'info' | 'warn' | 'error' }
  | { t: 'pong'; ts: number };

export type OutboundControl =
  | { t: 'hello.ack'; proto: number; name: string; tile: { w: number; h: number; dpi: number } }
  | { t: 'apps.refresh' }
  | { t: 'stream.request'; pkg: string }
  | { t: 'stream.recall'; id: number }
  | { t: 'stream.geometry'; id: number; w: number; h: number; dpi: number }
  | { t: 'stream.bitrate'; id: number; bps: number }
  | { t: 'stream.keyframe'; id: number }
  | {
      t: 'input.touch';
      id: number;
      action: 'down' | 'move' | 'up' | 'cancel';
      x: number;
      y: number;
      pointer: number;
    }
  | { t: 'input.scroll'; id: number; x: number; y: number; dx: number; dy: number }
  | { t: 'input.key'; id: number; action: 'down' | 'up'; keyCode: number; meta: number }
  | { t: 'input.text'; id: number; text: string }
  | { t: 'ping'; ts: number };

// ---------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------

export function encodeFrame(type: number, payload: Buffer): Buffer {
  const header = Buffer.allocUnsafe(5);
  header.writeUInt8(type, 0);
  header.writeUInt32BE(payload.length, 1);
  return Buffer.concat([header, payload]);
}

export function encodeControl(msg: OutboundControl): Buffer {
  return encodeFrame(FrameType.CONTROL, Buffer.from(JSON.stringify(msg), 'utf8'));
}

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

/**
 * Incremental frame reassembler. TCP gives us an arbitrarily chopped byte
 * stream, so every chunk is appended to a pending buffer and as many whole
 * frames as possible are handed to the callback.
 */
export class FrameParser {
  private pending: Buffer = Buffer.alloc(0);

  constructor(
    private readonly onFrame: (frame: Frame) => void,
    private readonly onError: (err: Error) => void,
  ) {}

  push(chunk: Buffer): void {
    this.pending = this.pending.length === 0 ? chunk : Buffer.concat([this.pending, chunk]);

    for (;;) {
      if (this.pending.length < 5) return;
      const type = this.pending.readUInt8(0);
      const length = this.pending.readUInt32BE(1);

      if (length > MAX_PAYLOAD) {
        this.onError(new Error(`frame payload ${length} exceeds ${MAX_PAYLOAD}`));
        return;
      }
      if (this.pending.length < 5 + length) return;

      // subarray shares memory with `pending`; copy so the consumer can keep it.
      const payload = Buffer.from(this.pending.subarray(5, 5 + length));
      this.pending = this.pending.subarray(5 + length);
      this.onFrame({ type, payload });
    }
  }
}

export interface VideoConfigPayload {
  streamId: number;
  csd: Buffer;
}

export function decodeVideoConfig(payload: Buffer): VideoConfigPayload | null {
  if (payload.length < 4) return null;
  return { streamId: payload.readUInt32BE(0), csd: Buffer.from(payload.subarray(4)) };
}

export interface VideoFramePayload {
  streamId: number;
  keyframe: boolean;
  ptsUs: number;
  data: Buffer;
}

export function decodeVideoFrame(payload: Buffer): VideoFramePayload | null {
  if (payload.length < 13) return null;
  return {
    streamId: payload.readUInt32BE(0),
    keyframe: (payload.readUInt8(4) & FRAME_FLAG_KEYFRAME) !== 0,
    // Presentation timestamps stay well inside Number.MAX_SAFE_INTEGER.
    ptsUs: Number(payload.readBigUInt64BE(5)),
    data: Buffer.from(payload.subarray(13)),
  };
}
