import type { NextConfig } from "next";

// Proxy /api/* to the Spring backend. In dev the backend is on :8080; in prod a
// reverse proxy fronts both on one origin. Doing it as a rewrite means the browser
// only ever talks to its own origin — no CORS config needed on the backend.
const backendOrigin = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // Emit a self-contained server bundle (.next/standalone) for a small Docker image.
  output: "standalone",
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${backendOrigin}/api/:path*` },
    ];
  },
};

export default nextConfig;
