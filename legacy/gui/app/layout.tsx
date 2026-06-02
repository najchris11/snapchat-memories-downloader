import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SnapVault Downloader",
  description: "Download, organize, and archive your Snapchat Memories with GPS metadata, overlay merging, and deduplication.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark" suppressHydrationWarning>
      <head>
        <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png" />
        <link rel="icon" type="image/png" sizes="16x16" href="/favicon-16x16.png" />
        <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;900&family=JetBrains+Mono:wght@400&display=swap" rel="stylesheet" />
      </head>
      <body className="antialiased overflow-hidden h-screen">
        {children}
      </body>
    </html>
  );
}
