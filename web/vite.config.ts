import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Proxy /api (HTTP + the SignalR WebSocket) to the ASP.NET Core backend so the browser sees a
// single origin — no CORS, and the refresh cookie is same-origin. See ARCHITECTURE.md.
const apiProxy = {
  '/api': {
    target: 'http://localhost:5025',
    changeOrigin: true,
    ws: true,
  },
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: apiProxy,
  },
  // `vite preview` serves the built bundle but does NOT inherit `server.proxy`, so without this
  // every /api call 404s against the production build. Used by `deploy/run-dev.sh --prod`.
  preview: {
    port: 4173,
    proxy: apiProxy,
  },
})
