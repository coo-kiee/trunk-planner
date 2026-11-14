import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// The local @cargo/* packages are symlinks into the monorepo, so the bundler root must contain them.
const monorepoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  outputFileTracingRoot: monorepoRoot,
  turbopack: { root: monorepoRoot },
};

export default nextConfig;
