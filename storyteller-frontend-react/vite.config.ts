import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Development keeps the same-origin proxy; static builds call the API directly.
const proxy = { '/v1': 'http://localhost:7090' };

export default defineConfig(({ command, mode }) => ({
  plugins: [react()],
  define: {
    __STORYTELLER_API_BASE_URL__: JSON.stringify(
      loadEnv(mode, '.', 'VITE_').VITE_API_BASE_URL ?? (command === 'serve' ? '' : 'http://localhost:7090')),
  },
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    proxy,
  },
}));
