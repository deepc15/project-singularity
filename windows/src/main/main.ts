import { readFile } from 'node:fs/promises';
import { extname, join, relative, isAbsolute, normalize } from 'node:path';
import { BrowserWindow, app, ipcMain, protocol, shell } from 'electron';

import { SingularServer, type PeerHandle } from './server';
import type { InboundControl, OutboundControl } from './protocol';

const isDev = process.argv.includes('--dev');

/**
 * The UI is served from `app://` rather than `file://`. A file:// document has
 * an opaque origin, so a strict `script-src 'self'` CSP blocks its own bundle;
 * a registered standard scheme gives the page a real origin and makes the CSP
 * in index.html actually enforceable.
 */
const SCHEME = 'app';
const HOST = 'bundle';

protocol.registerSchemesAsPrivileged([
  {
    scheme: SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true },
  },
]);

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

function registerBundleProtocol(): void {
  const rendererRoot = join(__dirname, '../renderer');

  // Read the file here rather than deferring to net.fetch('file://…'): routing
  // bundle reads through the network service makes startup depend on it, and a
  // crash there leaves the window blank with no error anywhere.
  protocol.handle(SCHEME, async (request) => {
    const url = new URL(request.url);
    const requested = url.pathname === '/' ? '/index.html' : url.pathname;
    const target = normalize(join(rendererRoot, decodeURIComponent(requested)));

    // Nothing outside the bundle directory is ever served.
    const rel = relative(rendererRoot, target);
    if (rel.startsWith('..') || isAbsolute(rel)) {
      return new Response('forbidden', { status: 403 });
    }

    try {
      const body = await readFile(target);
      return new Response(body, {
        status: 200,
        headers: { 'content-type': MIME[extname(target).toLowerCase()] ?? 'application/octet-stream' },
      });
    } catch (err) {
      console.error(`[singular] ${request.url}: ${(err as Error).message}`);
      return new Response('not found', { status: 404 });
    }
  });
}

let win: BrowserWindow | null = null;
let server: SingularServer | null = null;
let peer: PeerHandle | null = null;

function send(channel: string, ...args: unknown[]): void {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

function createWindow(): void {
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 720,
    minHeight: 480,
    backgroundColor: '#0d1014',
    title: 'Singular Desk',
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false,
    },
  });

  win.loadURL(`${SCHEME}://${HOST}/index.html`);
  if (isDev) win.webContents.openDevTools({ mode: 'detach' });

  // Nothing in this UI should ever navigate or spawn a second window.
  win.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: 'deny' };
  });

  // Load failures and CSP violations happen before any of our own code runs,
  // so they have to be caught here rather than in the renderer.
  win.webContents.on('did-finish-load', () => {
    console.log('[singular] ui loaded');
  });
  win.webContents.on('did-fail-load', (_e, code, description, url) => {
    console.error(`[singular] load failed ${code} ${description} ${url}`);
  });
  win.webContents.on('console-message', (_e, level, message, line, source) => {
    console.log(`[console:${level}] ${message} (${source}:${line})`);
  });

  win.on('closed', () => {
    win = null;
  });
}

function wireServer(): void {
  const srv = new SingularServer();
  server = srv;

  srv.on('log', (line) => {
    console.log('[singular]', line);
    send('desk:log', line);
  });

  srv.on('peer', (handle) => {
    // One phone at a time: a new connection supersedes the old one.
    if (peer && peer.id !== handle.id) peer.close('superseded by a new connection');
    peer = handle;
    send('desk:peer', { id: handle.id, address: handle.address, connected: true });
  });

  srv.on('peer-gone', (handle, reason) => {
    if (peer?.id !== handle.id) return;
    peer = null;
    send('desk:peer', { id: handle.id, address: handle.address, connected: false, reason });
  });

  srv.on('control', (handle, msg: InboundControl) => {
    if (peer?.id !== handle.id) return;
    send('desk:control', msg);
  });

  srv.on('video-config', (handle, cfg) => {
    if (peer?.id !== handle.id) return;
    send('desk:video-config', { streamId: cfg.streamId, csd: cfg.csd });
  });

  srv.on('video-frame', (handle, f) => {
    if (peer?.id !== handle.id) return;
    send('desk:video-frame', {
      streamId: f.streamId,
      keyframe: f.keyframe,
      ptsUs: f.ptsUs,
      data: f.data,
    });
  });

  srv.start().catch((err: Error) => {
    console.error('[singular] failed to start server', err);
    send('desk:fatal', `Could not start the Singular listener: ${err.message}`);
  });
}

ipcMain.on('desk:send', (_event, msg: OutboundControl) => {
  peer?.send(msg);
});

// Without this, a renderer exception is invisible unless DevTools is open.
ipcMain.on('desk:report', (_event, line: string) => {
  console.log('[renderer]', line);
});

ipcMain.handle('desk:set-tile-geometry', (_event, geom: { w: number; h: number; dpi: number }) => {
  if (server) server.tileGeometry = geom;
});

ipcMain.handle('desk:info', () => ({
  pcName: server?.pcName ?? '',
  connected: peer !== null,
  version: app.getVersion(),
}));

app.whenReady().then(() => {
  registerBundleProtocol();
  createWindow();
  wireServer();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  server?.stop();
  app.quit();
});
