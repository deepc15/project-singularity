import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';

import type { InboundControl, OutboundControl } from '../main/protocol';

export interface PeerStatus {
  id: string;
  address: string;
  connected: boolean;
  reason?: string;
}

export interface VideoConfigEvent {
  streamId: number;
  csd: Uint8Array;
}

export interface VideoFrameEvent {
  streamId: number;
  keyframe: boolean;
  ptsUs: number;
  data: Uint8Array;
}

export interface DeskApi {
  send(msg: OutboundControl): void;
  /** Renderer diagnostics, mirrored to the main process console. */
  report(line: string): void;
  setTileGeometry(geom: { w: number; h: number; dpi: number }): Promise<void>;
  info(): Promise<{ pcName: string; connected: boolean; version: string }>;
  onPeer(cb: (status: PeerStatus) => void): () => void;
  onControl(cb: (msg: InboundControl) => void): () => void;
  onVideoConfig(cb: (e: VideoConfigEvent) => void): () => void;
  onVideoFrame(cb: (e: VideoFrameEvent) => void): () => void;
  onLog(cb: (line: string) => void): () => void;
  onFatal(cb: (msg: string) => void): () => void;
}

function subscribe<T>(channel: string, cb: (value: T) => void): () => void {
  const listener = (_e: IpcRendererEvent, value: T) => cb(value);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.off(channel, listener);
}

const api: DeskApi = {
  send: (msg) => ipcRenderer.send('desk:send', msg),
  report: (line) => ipcRenderer.send('desk:report', line),
  setTileGeometry: (geom) => ipcRenderer.invoke('desk:set-tile-geometry', geom),
  info: () => ipcRenderer.invoke('desk:info'),
  onPeer: (cb) => subscribe('desk:peer', cb),
  onControl: (cb) => subscribe('desk:control', cb),
  onVideoConfig: (cb) => subscribe('desk:video-config', cb),
  onVideoFrame: (cb) => subscribe('desk:video-frame', cb),
  onLog: (cb) => subscribe('desk:log', cb),
  onFatal: (cb) => subscribe('desk:fatal', cb),
};

contextBridge.exposeInMainWorld('desk', api);
