import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Development keeps the same-origin proxy; static builds call the API on the same origin,
// so no host is baked into the bundle and the SPA can be served by the API under /app.
const proxy = { '/v1': 'http://localhost:7090' };

export default defineConfig(({ command, mode }) => ({
  plugins: [react()],
  base: command === 'serve' ? '/' : '/app/',
  define: {
    __STORYTELLER_API_BASE_URL__: JSON.stringify(loadEnv(mode, '.', 'VITE_').VITE_API_BASE_URL ?? ''),
  },
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    proxy,
  },
}));
