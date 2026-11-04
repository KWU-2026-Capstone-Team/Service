"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { api, errorText } from "@/lib/api";

const links = [
  ["/", "홈"],
  ["/analysis/", "영상 분석"],
  ["/history/", "이력"],
] as const;

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [status, setStatus] = useState("백엔드 연결 필요");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const update = (event: Event) => setStatus(`연결됨 · ${String((event as CustomEvent).detail).toUpperCase()}`);
    window.addEventListener("deeptector:status", update);
    return () => window.removeEventListener("deeptector:status", update);
  }, []);

  async function connect() {
    setBusy(true);
    try {
      const { model, reused } = await api.connect();
      setStatus(`${reused ? "세션 재사용" : "새 세션"} · ${model.inference_mode.toUpperCase()}`);
      window.dispatchEvent(new Event("deeptector:connected"));
    } catch (error) {
      setStatus(errorText(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell">
      <header className="site-header">
        <Link className="brand" href="/" aria-label="DeepTector 홈">
          <span className="brand-mark">DT</span>
          <span>DEEPTECTOR</span>
        </Link>
        <nav aria-label="주요 메뉴">
          {links.map(([href, label]) => (
            <Link key={href} href={href} className={pathname === href ? "active" : ""}>{label}</Link>
          ))}
        </nav>
        <button className="connection-button" onClick={connect} disabled={busy}>
          <span className="status-dot" /> {busy ? "연결 중…" : status}
        </button>
      </header>
      <main>{children}</main>
      <footer>
        <span>LOCAL FORENSIC WORKBENCH</span>
        <span>모델 신호는 조작의 확정적 증거가 아닙니다.</span>
      </footer>
    </div>
  );
}
