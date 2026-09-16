import { EventEmitter } from 'node:events';
import { createServer, type Server, type Socket } from 'node:net';
import { createSocket, type Socket as UdpSocket } from 'node:dgram';
import { hostname } from 'node:os';
import { randomUUID } from 'node:crypto';

import {
  DISCOVERY_PROBE,
  FrameParser,
  FrameType,
  PROTO_VERSION,
  TCP_PORT,
  UDP_PORT,
  decodeVideoConfig,
  decodeVideoFrame,
  encodeControl,
  type InboundControl,
  type OutboundControl,
  type VideoConfigPayload,
  type VideoFramePayload,
} from './protocol';

const PING_INTERVAL_MS = 3_000;
const PING_TIMEOUT_MS = 9_000;

export interface PeerHandle {
  readonly id: string;
  readonly address: string;
  send(msg: OutboundControl): void;
  close(reason: string): void;
}

interface ServerEvents {
  peer: [PeerHandle];
  'peer-gone': [PeerHandle, string];
  control: [PeerHandle, InboundControl];
  'video-config': [PeerHandle, VideoConfigPayload];
  'video-frame': [PeerHandle, VideoFramePayload];
  log: [string];
}

/**
 * Accepts Singular Cast connections and answers UDP discovery probes.
 *
 * Emitted events carry the peer handle so a future multi-phone UI can route
 * by device; today the renderer only ever renders one peer at a time.
 */
export class SingularServer extends EventEmitter<ServerEvents> {
  private tcp: Server | null = null;
  private udp: UdpSocket | null = null;
  private readonly peers = new Map<string, Socket>();
  private readonly instanceId = randomUUID();

  /** Preferred virtual-display geometry advertised to the phone on connect. */
  tileGeometry = { w: 1280, h: 800, dpi: 200 };

  get pcName(): string {
    return hostname();
  }

  async start(): Promise<void> {
    await this.startTcp();
    await this.startDiscovery();
  }

  stop(): void {
    for (const [, socket] of this.peers) socket.destroy();
    this.peers.clear();
    this.tcp?.close();
    this.udp?.close();
    this.tcp = null;
    this.udp = null;
  }

  private startTcp(): Promise<void> {
    return new Promise((resolve, reject) => {
      const server = createServer((socket) => this.onConnection(socket));
      server.once('error', reject);
      server.listen(TCP_PORT, '0.0.0.0', () => {
        server.off('error', reject);
        server.on('error', (err) => this.emit('log', `tcp error: ${err.message}`));
        this.emit('log', `listening on tcp/${TCP_PORT}`);
        this.tcp = server;
        resolve();
      });
    });
  }

  private startDiscovery(): Promise<void> {
    return new Promise((resolve, reject) => {
      const sock = createSocket({ type: 'udp4', reuseAddr: true });
      sock.once('error', reject);
      sock.on('message', (msg, rinfo) => {
        if (msg.toString('utf8').trim() !== DISCOVERY_PROBE) return;
        const reply = Buffer.from(
          JSON.stringify({
            t: 'singular.pc',
            proto: PROTO_VERSION,
            name: this.pcName,
            port: TCP_PORT,
            id: this.instanceId,
          }),
          'utf8',
        );
        sock.send(reply, rinfo.port, rinfo.address, (err) => {
          if (err) this.emit('log', `discovery reply failed: ${err.message}`);
        });
      });
      sock.bind(UDP_PORT, () => {
        sock.off('error', reject);
        sock.on('error', (err) => this.emit('log', `udp error: ${err.message}`));
        sock.setBroadcast(true);
        this.emit('log', `discovery responder on udp/${UDP_PORT}`);
        this.udp = sock;
        resolve();
      });
    });
  }

  private onConnection(socket: Socket): void {
    const id = randomUUID();
    const address = `${socket.remoteAddress ?? '?'}:${socket.remotePort ?? 0}`;
    socket.setNoDelay(true);
    this.peers.set(id, socket);

    let lastPong = Date.now();
    const handle: PeerHandle = {
      id,
      address,
      send: (msg) => {
        if (!socket.destroyed) socket.write(encodeControl(msg));
      },
      close: (reason) => {
        this.emit('log', `closing ${address}: ${reason}`);
        socket.destroy();
      },
    };

    const parser = new FrameParser(
      (frame) => {
        switch (frame.type) {
          case FrameType.CONTROL: {
            let msg: InboundControl;
            try {
              msg = JSON.parse(frame.payload.toString('utf8')) as InboundControl;
            } catch {
              this.emit('log', `bad control json from ${address}`);
              return;
            }
            if (msg.t === 'pong') {
              lastPong = Date.now();
              return;
            }
            this.emit('control', handle, msg);
            return;
          }
          case FrameType.VIDEO_CONFIG: {
            const cfg = decodeVideoConfig(frame.payload);
            if (cfg) this.emit('video-config', handle, cfg);
            return;
          }
          case FrameType.VIDEO_FRAME: {
            const f = decodeVideoFrame(frame.payload);
            if (f) this.emit('video-frame', handle, f);
            return;
          }
          default:
            // Unknown frame types are ignored so a newer phone can still talk to us.
            return;
        }
      },
      (err) => {
        this.emit('log', `framing error from ${address}: ${err.message}`);
        socket.destroy();
      },
    );

    const ping = setInterval(() => {
      if (Date.now() - lastPong > PING_TIMEOUT_MS) {
        handle.close('ping timeout');
        return;
      }
      handle.send({ t: 'ping', ts: Date.now() });
    }, PING_INTERVAL_MS);

    socket.on('data', (chunk) => parser.push(chunk));
    socket.on('error', (err) => this.emit('log', `socket error ${address}: ${err.message}`));
    socket.on('close', () => {
      clearInterval(ping);
      this.peers.delete(id);
      this.emit('peer-gone', handle, 'closed');
    });

    this.emit('log', `peer connected: ${address}`);
    this.emit('peer', handle);
    handle.send({
      t: 'hello.ack',
      proto: PROTO_VERSION,
      name: this.pcName,
      tile: this.tileGeometry,
    });
  }
}
