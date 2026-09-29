import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const API_URL = "http://localhost:8081";

/**
 * In development the browser only talks to the Vite server, so API calls are same-origin.
 * Dropping the Origin header stops the backend's CORS check from rejecting them when Vite
 * runs on another port (e.g. 5174) or is opened as 127.0.0.1.
 */
const toApi = {
  target: API_URL,
  configure: (proxy) => {
    proxy.on("proxyReq", (proxyRequest) => proxyRequest.removeHeader("origin"));
  },
};

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": toApi,
      "/images": toApi,
      "/videos": toApi,
    },
  },
});
