/**
 * H.264 Annex-B decoding via WebCodecs.
 *
 * MediaCodec on the phone emits Annex-B byte streams. WebCodecs treats an
 * `avc` config without a `description` as Annex-B, so frames can be fed
 * straight through with no bitstream rewriting.
 */

/** Strip Annex-B start codes and return each NAL unit's payload. */
function* nalUnits(buf: Uint8Array): Generator<Uint8Array> {
  let i = 0;
  let start = -1;
  while (i + 2 < buf.length) {
    if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
      if (start >= 0) yield buf.subarray(start, i);
      i += 3;
      start = i;
    } else if (
      i + 3 < buf.length &&
      buf[i] === 0 &&
      buf[i + 1] === 0 &&
      buf[i + 2] === 0 &&
      buf[i + 3] === 1
    ) {
      if (start >= 0) yield buf.subarray(start, i);
      i += 4;
      start = i;
    } else {
      i++;
    }
  }
  if (start >= 0 && start < buf.length) yield buf.subarray(start);
}

/**
 * Build the `avc1.PPCCLL` codec string from the SPS, so the decoder is
 * configured for the profile the phone's encoder actually chose rather than a
 * guess that fails on high-profile streams.
 */
function codecStringFromCsd(csd: Uint8Array): string {
  for (const nal of nalUnits(csd)) {
    const nalType = (nal[0] ?? 0) & 0x1f;
    if (nalType !== 7 || nal.length < 4) continue; // 7 = SPS
    const profile = nal[1]!;
    const constraints = nal[2]!;
    const level = nal[3]!;
    const hex = (n: number) => n.toString(16).padStart(2, '0');
    return `avc1.${hex(profile)}${hex(constraints)}${hex(level)}`;
  }
  return 'avc1.42e01e'; // Constrained Baseline 3.0 — safe last resort.
}

export interface DecoderCallbacks {
  onFrame(frame: VideoFrame): void;
  /** Called when the decoder had to be torn down and needs a fresh IDR. */
  onNeedKeyframe(): void;
  onError(message: string): void;
}

export class H264Decoder {
  private decoder: VideoDecoder | null = null;
  private csd: Uint8Array | null = null;
  private codec = 'avc1.42e01e';
  private sawKeyframe = false;
  private closed = false;
  /** Frames dropped because the decoder queue is already saturated. */
  droppedFrames = 0;
  decodedFrames = 0;

  constructor(private readonly cb: DecoderCallbacks) {}

  configure(csd: Uint8Array): void {
    this.csd = csd;
    this.codec = codecStringFromCsd(csd);
    this.reset();
  }

  private reset(): void {
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
        // A decode error is usually one corrupt access unit; recover rather
        // than leaving a dead tile on screen.
        this.reset();
        this.cb.onNeedKeyframe();
      },
    });

    // No `description` => Annex-B, which is what MediaCodec gives us.
    decoder.configure({ codec: this.codec, optimizeForLatency: true });
    this.decoder = decoder;
  }

  decode(data: Uint8Array, keyframe: boolean, ptsUs: number): void {
    const decoder = this.decoder;
    if (!decoder || decoder.state !== 'configured') return;

    if (!this.sawKeyframe) {
      if (!keyframe) return; // Nothing useful can come of a delta frame here.
      this.sawKeyframe = true;
    }

    // Backpressure: if the decoder is already behind, skip non-keyframes
    // instead of building an unbounded queue and drifting seconds behind.
    if (decoder.decodeQueueSize > 4 && !keyframe) {
      this.droppedFrames++;
      return;
    }

    // Re-prefixing SPS/PPS on every IDR makes mid-stream recovery work.
    const payload =
      keyframe && this.csd
        ? (() => {
            const merged = new Uint8Array(this.csd.length + data.length);
            merged.set(this.csd, 0);
            merged.set(data, this.csd.length);
            return merged;
          })()
        : data;

    try {
      decoder.decode(
        new EncodedVideoChunk({
          type: keyframe ? 'key' : 'delta',
          timestamp: ptsUs,
          data: payload,
        }),
      );
    } catch (err) {
      this.cb.onError(err instanceof Error ? err.message : String(err));
      this.reset();
      this.cb.onNeedKeyframe();
    }
  }

  private teardown(): void {
    const decoder = this.decoder;
    this.decoder = null;
    if (!decoder) return;
    try {
      if (decoder.state !== 'closed') decoder.close();
    } catch {
      // Already closed by the platform — nothing to do.
    }
  }

  close(): void {
    this.closed = true;
    this.teardown();
  }
}
