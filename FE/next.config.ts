import type { NextConfig } from "next";
import { BE_URL } from "./src/utils/be-url.mjs";

const nextConfig: NextConfig = {
  /* config options here */
    async rewrites() {
      return [
        {
          source: '/api/:path*',
          destination: `${BE_URL}/api/:path*`,
        },
      ];
    },
    images: {
       remotePatterns: [
         {
           protocol: 'https',
           hostname: 'images.unsplash.com',
         },
       ],
     },
};

export default nextConfig;
