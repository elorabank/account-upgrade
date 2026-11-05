import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The build goes straight into the jar's static/ folder; the api-gateway serves it on the same origin as the API,
// so the login session cookie and the CSRF cookie just work.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'target/classes/static',
    emptyOutDir: true,
  },
});
