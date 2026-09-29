import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Proxied rather than called cross-origin, so REST is same-origin: no CORS, and the session
    // cookie is set for this page's host. The browser's Origin header is forwarded unchanged,
    // which is why http://localhost:5173 is on the server's allowed-origins list.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    // ChatClient takes its socket, API and storage as parameters, so its tests need no DOM.
    environment: 'node',
  },
});
