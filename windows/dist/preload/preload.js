"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// src/preload/preload.ts
var preload_exports = {};
module.exports = __toCommonJS(preload_exports);
var import_electron = require("electron");
function subscribe(channel, cb) {
  const listener = (_e, value) => cb(value);
  import_electron.ipcRenderer.on(channel, listener);
  return () => import_electron.ipcRenderer.off(channel, listener);
}
var api = {
  send: (msg) => import_electron.ipcRenderer.send("desk:send", msg),
  report: (line) => import_electron.ipcRenderer.send("desk:report", line),
  setTileGeometry: (geom) => import_electron.ipcRenderer.invoke("desk:set-tile-geometry", geom),
  info: () => import_electron.ipcRenderer.invoke("desk:info"),
  onPeer: (cb) => subscribe("desk:peer", cb),
  onControl: (cb) => subscribe("desk:control", cb),
  onVideoConfig: (cb) => subscribe("desk:video-config", cb),
  onVideoFrame: (cb) => subscribe("desk:video-frame", cb),
  onLog: (cb) => subscribe("desk:log", cb),
  onFatal: (cb) => subscribe("desk:fatal", cb)
};
import_electron.contextBridge.exposeInMainWorld("desk", api);
//# sourceMappingURL=preload.js.map
