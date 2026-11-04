import type { Metadata } from "next";
import "./globals.css";
import { AppShell } from "@/components/AppShell";

export const metadata: Metadata = {
  title: "DeepTector · 딥페이크 포렌식",
  description: "로컬 영상 분석과 딥페이크 판별 훈련을 위한 통합 포렌식 도구",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body><AppShell>{children}</AppShell></body>
    </html>
  );
}
