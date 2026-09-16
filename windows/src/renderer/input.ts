/**
 * Translation of Windows mouse/keyboard input into the Android events that
 * Singular Cast injects into the cast app's display.
 */

/** Android `KeyEvent` keycodes we can map from a `KeyboardEvent.key`. */
const KEYCODES: Record<string, number> = {
  Backspace: 67,
  Tab: 61,
  Enter: 66,
  Escape: 111,
  ' ': 62,
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
  F12: 142,
};

/** Android `KeyEvent` meta-state bits. */
const META = {
  SHIFT_ON: 0x00000001,
  ALT_ON: 0x00000002,
  SYM_ON: 0x00000004,
  CTRL_ON: 0x00001000,
  META_ON: 0x00010000,
} as const;

export function metaStateOf(e: KeyboardEvent | MouseEvent): number {
  let meta = 0;
  if (e.shiftKey) meta |= META.SHIFT_ON;
  if (e.altKey) meta |= META.ALT_ON;
  if (e.ctrlKey) meta |= META.CTRL_ON;
  if (e.metaKey) meta |= META.META_ON;
  return meta;
}

export interface KeyTranslation {
  kind: 'key';
  keyCode: number;
  meta: number;
}

export interface TextTranslation {
  kind: 'text';
  text: string;
}

/**
 * Printable characters are sent as text so the phone can commit them straight
 * into the focused field (correct for every keyboard layout); everything else
 * becomes a real keycode.
 */
export function translateKey(e: KeyboardEvent): KeyTranslation | TextTranslation | null {
  const mapped = KEYCODES[e.key];
  if (mapped !== undefined) return { kind: 'key', keyCode: mapped, meta: metaStateOf(e) };

  // A single-code-point key with no Ctrl/Alt/Meta modifier is text input.
  if ([...e.key].length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
    return { kind: 'text', text: e.key };
  }
  return null;
}

/** Android system keys, exposed as buttons on each tile. */
export const SYSTEM_KEYS = {
  back: 4,
  home: 3,
  recents: 187,
  volumeUp: 24,
  volumeDown: 25,
} as const;

export interface Normalized {
  x: number;
  y: number;
}

/**
 * Map a pointer position within the letterboxed video rect to a 0..1
 * coordinate on the Android display. Returns null for clicks that land on the
 * letterbox rather than the app.
 */
export function normalizePointer(
  clientX: number,
  clientY: number,
  videoRect: DOMRect,
): Normalized | null {
  if (videoRect.width <= 0 || videoRect.height <= 0) return null;
  const x = (clientX - videoRect.left) / videoRect.width;
  const y = (clientY - videoRect.top) / videoRect.height;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return { x, y };
}

/** Clamp to the display so a drag that leaves the tile still tracks sensibly. */
export function clampPointer(
  clientX: number,
  clientY: number,
  videoRect: DOMRect,
): Normalized {
  const x = (clientX - videoRect.left) / Math.max(videoRect.width, 1);
  const y = (clientY - videoRect.top) / Math.max(videoRect.height, 1);
  return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) };
}

/**
 * Compute the largest rect with `srcW:srcH` that fits inside the box —
 * the doc's "the application will be automatically fit into the pc client
 * screen" requirement.
 */
export function fitRect(
  boxW: number,
  boxH: number,
  srcW: number,
  srcH: number,
): { w: number; h: number; left: number; top: number } {
  if (srcW <= 0 || srcH <= 0) return { w: boxW, h: boxH, left: 0, top: 0 };
  const scale = Math.min(boxW / srcW, boxH / srcH);
  const w = Math.max(1, Math.floor(srcW * scale));
  const h = Math.max(1, Math.floor(srcH * scale));
  return { w, h, left: Math.floor((boxW - w) / 2), top: Math.floor((boxH - h) / 2) };
}
