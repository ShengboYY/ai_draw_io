import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Production is uploaded as immutable static files to the private S3 origin.
  output: "export",
  trailingSlash: true,
  images: {
    unoptimized: true,
  },
};

export default nextConfig;
