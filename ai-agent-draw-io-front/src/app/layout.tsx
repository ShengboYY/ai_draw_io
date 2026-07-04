import type { Metadata } from "next";
import { Space_Grotesk, Public_Sans, JetBrains_Mono } from "next/font/google";
import "./globals.css";

// Space Grotesk carries the "personality" — headings, the FreeDraw wordmark, buttons.
const displayFont = Space_Grotesk({
  variable: "--font-space-grotesk",
  subsets: ["latin"],
  display: "swap",
});

// Public Sans is the neutral workhorse for body copy, labels, and inputs.
const bodyFont = Public_Sans({
  variable: "--font-public-sans",
  subsets: ["latin"],
  display: "swap",
});

// JetBrains Mono is reserved for technical micro-labels (dates, ⌘K, percentages).
const monoFont = JetBrains_Mono({
  variable: "--font-jetbrains-mono",
  subsets: ["latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "FreeDraw",
  description: "Interactive AI diagramming",
};

import Script from "next/script";

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <Script src="/env-config.js" strategy="beforeInteractive" />
      </head>
      <body
        className={`${displayFont.variable} ${bodyFont.variable} ${monoFont.variable} antialiased`}
        // Browser extensions can inject attributes before React hydrates the body.
        suppressHydrationWarning
      >
        {children}
      </body>
    </html>
  );
}
