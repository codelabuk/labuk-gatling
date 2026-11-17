import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// built into ../static/dist and served by Flask; `npm run dev` proxies the API to a local Flask on :5000
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../static/dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:5000',
      '/reports': 'http://localhost:5000',
    },
  },
})
