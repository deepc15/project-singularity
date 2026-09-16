import { Tile } from './tile';
import type { AppEntry, DeviceCaps, DeviceInfo, InboundControl, OutboundControl } from '../main/protocol';

const desk = window.desk;
const send = (msg: OutboundControl): void => desk.send(msg);

const el = <T extends HTMLElement>(sel: string): T => {
  const node = document.querySelector<T>(sel);
  if (!node) throw new Error(`missing element: ${sel}`);
  return node;
};

const grid = el<HTMLElement>('#grid');
const emptyState = el<HTMLElement>('#empty');
const statusPill = el<HTMLElement>('#status');
const deviceLine = el<HTMLElement>('#device');
const capsLine = el<HTMLElement>('#caps');
const drawer = el<HTMLElement>('#drawer');
const appList = el<HTMLElement>('#app-list');
const appFilter = el<HTMLInputElement>('#app-filter');
const logPanel = el<HTMLElement>('#log');
const toastHost = el<HTMLElement>('#toasts');
const hostLine = el<HTMLElement>('#host');

const tiles = new Map<number, Tile>();
let inventory: AppEntry[] = [];
/** Icons arrive once per package; keep them for later inventory refreshes. */
const iconCache = new Map<string, string>();

// ---------------------------------------------------------------------------
// Toasts and log
// ---------------------------------------------------------------------------

function toast(text: string, level: 'info' | 'warn' | 'error' = 'info'): void {
  const node = document.createElement('div');
  node.className = `toast ${level}`;
  node.textContent = text;
  toastHost.append(node);
  setTimeout(() => node.classList.add('out'), 4200);
  setTimeout(() => node.remove(), 4800);
}

function log(line: string): void {
  const node = document.createElement('div');
  node.className = 'log-line';
  node.textContent = `${new Date().toLocaleTimeString()}  ${line}`;
  logPanel.prepend(node);
  while (logPanel.childElementCount > 300) logPanel.lastElementChild?.remove();
  desk.report(line);
}

window.addEventListener('error', (e) =>
  desk.report(`uncaught: ${e.message} (${e.filename}:${e.lineno})`),
);
window.addEventListener('unhandledrejection', (e) =>
  desk.report(`unhandled rejection: ${String(e.reason)}`),
);

// ---------------------------------------------------------------------------
// Tiles
// ---------------------------------------------------------------------------

function refreshLayout(): void {
  const count = tiles.size;
  emptyState.hidden = count > 0;
  grid.hidden = count === 0;
  // A focused tile takes the whole stage; otherwise pack into a square-ish grid.
  const focused = grid.querySelector('.tile.focused') !== null;
  const cols = focused ? 1 : Math.max(1, Math.ceil(Math.sqrt(count)));
  grid.style.setProperty('--cols', String(cols));
  grid.classList.toggle('has-focus', focused);
}

function openTile(msg: Extract<InboundControl, { t: 'stream.start' }>): void {
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
    },
  });

  tiles.set(msg.id, tile);
  grid.append(tile.root);
  refreshLayout();
  tile.root.focus();
  log(`stream ${msg.id} started: ${msg.app.label} (${msg.mode}, ${msg.w}×${msg.h})`);
}

grid.addEventListener('tile-focus-toggle', (ev) => {
  const focusedId = (ev as CustomEvent<number>).detail;
  // Only one tile may be focused at a time.
  for (const [id, tile] of tiles) {
    if (id !== focusedId) tile.root.classList.remove('focused');
  }
  refreshLayout();
});

// ---------------------------------------------------------------------------
// App drawer — lets the PC start a cast too, not just the phone
// ---------------------------------------------------------------------------

function renderAppList(): void {
  const needle = appFilter.value.trim().toLowerCase();
  const visible = inventory
    .filter((a) => !needle || a.label.toLowerCase().includes(needle) || a.pkg.includes(needle))
    .sort((a, b) => {
      if (a.running !== b.running) return a.running ? -1 : 1;
      if (a.recent !== b.recent) return a.recent ? -1 : 1;
      return a.label.localeCompare(b.label);
    });

  appList.replaceChildren();
  if (inventory.length === 0) {
    const hint = document.createElement('p');
    hint.className = 'muted pad';
    hint.textContent = 'Connect a phone to see its apps.';
    appList.append(hint);
    return;
  }

  for (const entry of visible) {
    const row = document.createElement('button');
    row.className = 'app-row';
    row.type = 'button';
    row.title = entry.pkg;

    const icon = iconCache.get(entry.pkg);
    if (icon) {
      const img = document.createElement('img');
      img.src = `data:image/png;base64,${icon}`;
      img.alt = '';
      row.append(img);
    } else {
      const ph = document.createElement('span');
      ph.className = 'app-icon-placeholder';
      ph.textContent = entry.label.slice(0, 1).toUpperCase();
      row.append(ph);
    }

    const name = document.createElement('span');
    name.className = 'app-name';
    name.textContent = entry.label;
    row.append(name);

    if (entry.running || entry.recent) {
      const dot = document.createElement('span');
      dot.className = entry.running ? 'dot running' : 'dot recent';
      dot.title = entry.running ? 'Running' : 'Recently used';
      row.append(dot);
    }

    row.addEventListener('click', () => {
      send({ t: 'stream.request', pkg: entry.pkg });
      toast(`Asking the phone to cast ${entry.label}…`);
    });
    appList.append(row);
  }
}

appFilter.addEventListener('input', renderAppList);
el<HTMLElement>('#refresh-apps').addEventListener('click', () => send({ t: 'apps.refresh' }));
el<HTMLElement>('#toggle-drawer').addEventListener('click', () => {
  drawer.classList.toggle('open');
});
el<HTMLElement>('#toggle-log').addEventListener('click', () => {
  logPanel.classList.toggle('open');
});

// ---------------------------------------------------------------------------
// Connection state
// ---------------------------------------------------------------------------

function setConnected(connected: boolean, address?: string): void {
  statusPill.textContent = connected ? `Connected · ${address ?? ''}` : 'Waiting for a phone';
  statusPill.classList.toggle('on', connected);
  if (connected) return;

  for (const tile of [...tiles.values()]) tile.dispose();
  tiles.clear();
  inventory = [];
  deviceLine.textContent = '';
  capsLine.replaceChildren();
  renderAppList();
  refreshLayout();
}

function renderCaps(device: DeviceInfo, caps: DeviceCaps): void {
  deviceLine.textContent = `${device.name} · ${device.model} · Android SDK ${device.sdk} · ${device.w}×${device.h}`;
  capsLine.replaceChildren();
  const chips: Array<[string, boolean, string]> = [
    ['per-app displays', caps.privileged, 'Shizuku/ADB is available, so each app gets its own display'],
    ['gesture input', caps.accessibility, 'Accessibility service enabled — fallback input path'],
    ['screen mirror', caps.mirror, 'MediaProjection whole-screen capture available'],
  ];
  for (const [label, on, title] of chips) {
    const chip = document.createElement('span');
    chip.className = `chip ${on ? 'ok' : 'off'}`;
    chip.textContent = label;
    chip.title = title;
    capsLine.append(chip);
  }
  if (!caps.privileged) {
    toast(
      'Phone has no Shizuku/ADB access — falling back to whole-screen mirroring, one tile only.',
      'warn',
    );
  }
}

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

desk.onPeer((status) => {
  setConnected(status.connected, status.address);
  log(status.connected ? `phone connected (${status.address})` : `phone gone: ${status.reason ?? ''}`);
});

desk.onControl((msg) => {
  switch (msg.t) {
    case 'hello':
      renderCaps(msg.device, msg.caps);
      break;

    case 'apps':
      for (const entry of msg.list) {
        if (entry.iconPng) iconCache.set(entry.pkg, entry.iconPng);
      }
      inventory = msg.list;
      renderAppList();
      break;

    case 'stream.start':
      openTile(msg);
      break;

    case 'stream.stop':
      tiles.get(msg.id)?.dispose();
      log(`stream ${msg.id} stopped (${msg.reason})`);
      break;

    case 'stream.resized':
      tiles.get(msg.id)?.resized(msg.w, msg.h, msg.dpi);
      break;

    case 'ime.show':
      tiles.get(msg.id)?.setImeHint(true);
      break;

    case 'ime.hide':
      tiles.get(msg.id)?.setImeHint(false);
      break;

    case 'toast':
      toast(msg.text, msg.level);
      log(`phone: ${msg.text}`);
      break;
  }
});

desk.onVideoConfig(({ streamId, csd }) => tiles.get(streamId)?.configure(csd));
desk.onVideoFrame(({ streamId, keyframe, ptsUs, data }) =>
  tiles.get(streamId)?.pushFrame(data, keyframe, ptsUs),
);
desk.onLog(log);
desk.onFatal((msg) => {
  toast(msg, 'error');
  log(msg);
});

window.addEventListener('resize', refreshLayout);

void desk.info().then((info) => {
  hostLine.textContent = `${info.pcName} · tcp 8787`;
  setConnected(info.connected);
});

setConnected(false);
refreshLayout();
