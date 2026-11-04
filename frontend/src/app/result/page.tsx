"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api, errorText } from "@/lib/api";
import type { GameResult } from "@/lib/types";

function ResultContent() {
  const router = useRouter();
  const id = useSearchParams().get("id");
  const [result, setResult] = useState<GameResult | null>(null);
  const [message, setMessage] = useState("서버의 최종 결과를 불러오는 중입니다…");

  useEffect(() => {
    if (!id) return;
    let active = true;
    void api.connect().then(() => api.result(id)).then(value => { if (active) setResult(value); }).catch((error) => { if (active) setMessage(errorText(error)); });
    return () => { active = false; };
  }, [id]);

  if (!id) return <div className="empty">게임 ID가 없습니다. 이력에서 결과를 다시 열어 주세요.</div>;
  if (!result) return <div className="empty">{message}</div>;
  const userAccuracy = result.user_accuracy == null ? null : `${(result.user_accuracy * 100).toFixed(1)}%`;
  const aiAccuracy = result.ai_accuracy == null ? null : `${(result.ai_accuracy * 100).toFixed(1)}%`;

  return (
    <div className="result-page">
      <header className="page-head"><div><p className="kicker">FORENSIC REPORT</p><h1>게임 결과</h1><p className="muted id">{id}</p></div><div className="grade">{result.grade}</div></header>
      <div className="score-hero"><b>{result.score.toLocaleString()}</b><span>SERVER SCORE</span></div>
      <div className="metric-grid">
        <div className="metric"><b>{result.played_rounds}</b><span>PLAYED</span></div>
        <div className="metric"><b>{result.skipped_rounds}</b><span>SKIPPED</span></div>
        <div className="metric"><b>{userAccuracy ?? "—"}</b><span>MY ACCURACY</span></div>
        <div className="metric"><b>×{result.max_streak}</b><span>MAX COMBO</span></div>
      </div>
      <section className="card ai-summary">
        <div><p className="kicker">AI EVALUATION</p><h2>{result.ai_evaluated_rounds > 0 ? `${result.ai_evaluated_rounds}개 라운드 평가` : "AI 평가 없음"}</h2></div>
        <div><b>{aiAccuracy ?? "—"}</b><span>{result.ai_score == null ? "점수 없음" : `AI SCORE ${result.ai_score}`}</span></div>
        <p className="muted">평가 스냅샷이 없는 영상에는 AI 점수나 정확도를 추정해 표시하지 않습니다.</p>
      </section>
      <section>
        <h2>라운드 기록</h2>
        <div className="evidence-list">
          {result.rounds.map((item) => <article key={item.round} className={`evidence ${item.skipped ? "skipped" : item.user_correct ? "correct" : "wrong"}`}>
            <span>{String(item.round).padStart(2, "0")}</span>
            <div><b>{item.skipped ? "SKIPPED" : item.ground_truth}</b><p>{item.skipped ? "미디어 재생 불가로 건너뜀" : `내 답 ${item.answer} · ${item.user_correct ? "정답" : "오답"}`}</p></div>
            <strong>{item.skipped ? "—" : item.points > 0 ? `+${item.points}` : "0"}</strong>
          </article>)}
        </div>
      </section>
      <div className="button-row"><button onClick={() => router.push("/")}>새 게임</button><button className="secondary" onClick={() => router.push("/history/")}>이력 보기</button></div>
    </div>
  );
}

export default function ResultPage() {
  return <Suspense fallback={<div className="empty">결과를 불러오는 중…</div>}><ResultContent /></Suspense>;
}
