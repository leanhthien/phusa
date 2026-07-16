import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Phù Sa — Vietnamese tech news",
  description: "Crawled and deduped Vietnamese tech & startup news.",
};

// System font stack on purpose: no next/font/google fetch at build (one less
// network dependency), and it looks native everywhere.
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi" className="h-full antialiased">
      <body className="min-h-full">{children}</body>
    </html>
  );
}
