import type { DeskApi } from '../preload/preload';

declare global {
  interface Window {
    desk: DeskApi;
  }
}
