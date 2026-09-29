/**
 * Where the WebSocket server is. Default: this page's host on port 8081, which is what local dev
 * needs (Vite on :5173, Spring on :8080, the hand-written WS server on :8081).
 *
 * VITE_WS_URL overrides it at build time. A value starting with "/" is taken as a path on this
 * page's own origin (for example "/ws" once nginx serves everything from one origin), with ws: or
 * wss: chosen to match the page, since a page served over https may only open wss: sockets.
 */
export function wsUrl(): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const configured = import.meta.env.VITE_WS_URL as string | undefined;
  if (configured?.startsWith('/')) return `${scheme}//${location.host}${configured}`;
  if (configured) return configured;
  // hostname, not host: the same machine the page came from, but the WS port instead of the page's.
  // It must be the same hostname too (localhost vs 127.0.0.1), because the cookie is host-only.
  return `${scheme}//${location.hostname}:8081/`;
}
