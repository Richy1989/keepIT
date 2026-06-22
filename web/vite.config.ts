import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In dev, proxy /api (HTTP + the SignalR WebSocket later) to the ASP.NET Core backend so the
// browser sees a single origin — no CORS, and the refresh cookie is same-origin. See ARCHITECTURE.md.
// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:5025',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
