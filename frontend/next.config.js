/** @type {import('next').NextConfig} */
const apiTarget = process.env.API_PROXY_TARGET || 'http://localhost:8080';

const nextConfig = {
  reactStrictMode: true,
  eslint: { ignoreDuringBuilds: true },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiTarget}/api/:path*`,
      },
      {
        source: '/oauth2/:path*',
        destination: `${apiTarget}/oauth2/:path*`,
      },
      {
        // Google redirects here after sign-in; proxying it keeps the session cookie on this origin.
        source: '/login/oauth2/:path*',
        destination: `${apiTarget}/login/oauth2/:path*`,
      },
    ];
  },
};

module.exports = nextConfig;
