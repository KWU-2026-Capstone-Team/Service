"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { Difficulty } from "@/lib/types";

const difficulties: { value: Difficulty; label: string; sub: string }[] = [
  { value: "easy", label: "EASY", sub: "쉬움" },
  { value: "medium", label: "MEDIUM", sub: "보통" },
  { value: "hard", label: "HARD", sub: "어려움" },
  { value: "all", label: "ALL", sub: "전체" },
];

export default function HomePage() {
  const router = useRouter();
  const [rounds, setRounds] = useState(10);
  const [difficulty, setDifficulty] = useState<Difficulty>("all");

  return (
    <div className="home-page">
      <section className="hero">
        <div className="eyebrow"><span /> FORENSIC ANALYSIS SYSTEM <span /></div>
        <h1>영상의 흔적을<br /><em>직접 확인하세요.</em></h1>
        <p>로컬 모델 분석과 사람 대 AI 판별 훈련을 하나의 세션에서 진행합니다.</p>
        <div className="hero-actions">
          <button onClick={() => router.push("/analysis/")}>영상 분석 시작</button>
          <button className="secondary" onClick={() => document.getElementById("game-setup")?.scrollIntoView()}>판별 게임 설정</button>
        </div>
        <div className="forensic-visual" aria-hidden="true">
          <div className="scan-frame"><span className="scan-line" /><div className="face-grid">01<br />101<br />010</div></div>
          <div className="signal-bars">{[35, 62, 46, 84, 58, 92, 43, 74].map((n, i) => <i key={i} style={{ height: `${n}%` }} />)}</div>
        </div>
      </section>

      <section className="feature-grid" aria-label="주요 기능">
        <article className="feature-card"><span>01</span><h2>실제 분석 상태</h2><p>업로드부터 대기, 처리, 결과와 XAI 이미지까지 서버 상태 그대로 표시합니다.</p></article>
        <article className="feature-card"><span>02</span><h2>정답 비공개 게임</h2><p>답변 전에는 라벨과 AI 평가를 받지 않고, 서버가 점수와 콤보를 계산합니다.</p></article>
        <article className="feature-card"><span>03</span><h2>세션 기반 이력</h2><p>같은 탭의 세션을 재사용해 분석과 게임을 다시 열거나 안전하게 삭제합니다.</p></article>
      </section>

      <section id="game-setup" className="panel setup-panel">
        <div>
          <p className="kicker">HUMAN VS AI</p>
          <h2>딥페이크 판별 훈련</h2>
          <p className="muted">영상이 실제인지 조작인지 판단하세요. R / F 키로 답하고 Space로 다음 라운드로 이동합니다.</p>
        </div>
        <div className="setup-controls">
          <fieldset>
            <legend>난이도</legend>
            <div className="segmented four">
              {difficulties.map((item) => <button key={item.value} className={difficulty === item.value ? "selected" : ""} onClick={() => setDifficulty(item.value)}><b>{item.label}</b><small>{item.sub}</small></button>)}
            </div>
          </fieldset>
          <fieldset>
            <legend>라운드</legend>
            <div className="segmented">
              {[5, 10, 15].map((count) => <button key={count} className={rounds === count ? "selected" : ""} onClick={() => setRounds(count)}>{count}</button>)}
            </div>
          </fieldset>
          <button className="wide" onClick={() => router.push(`/game/?rounds=${rounds}&difficulty=${difficulty}`)}>게임 시작 →</button>
        </div>
      </section>
    </div>
  );
}
