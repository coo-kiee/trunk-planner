import type { Metadata, Viewport } from 'next';
import { headers } from 'next/headers';
import type { ReactNode } from 'react';
import '@cargo/design-tokens/tokens.css';
import '../shared/ui/semantic-tokens.css';
import './globals.css';
import { PAIRING_BOOTSTRAP } from '../features/pairing/bootstrap-script.ts';

export const metadata: Metadata = {
  title: '적재 플래너 · 물품 등록',
  description: '차량 적재 플래너로 보낼 물품의 실측 치수와 취급조건을 등록합니다.',
  referrer: 'no-referrer',
  robots: { index: false, follow: false },
};
export const viewport: Viewport = { width: 'device-width', initialScale: 1 };

export default async function RootLayout({ children }: { children: ReactNode }) {
  const nonce = (await headers()).get('x-nonce') ?? undefined;
  return (
    <html lang="ko">
      <head>
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: PAIRING_BOOTSTRAP }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
