"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, errorText } from "@/lib/api";
import type { AnalysisSummary, GameSummary } from "@/lib/types";

export default function HistoryPage() {
  const router = useRouter();
  const [analyses, setAnalyses] = useState<AnalysisSummary[]>([]);
  const [games, setGames] = useState<GameSummary[]>([]);
  const [message, setMessage] = useState("세션 이력을 불러오는 중입니다…");

  const refresh = useCallback(async () => {
    try {
      await api.connect();
      const [analysisData, gameData] = await Promise.all([api.analyses(), api.games()]);
      setAnalyses(analysisData.items);
      setGames(gameData.items);
      setMessage("현재 브라우저 탭의 서버 세션 이력입니다.");
    } catch (error) { setMessage(errorText(error)); }
  }, []);

  useEffect(() => {
    // refresh updates state only after its asynchronous session/history requests.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  async function deleteAnalysis(id: string) {
    if (!window.confirm("이 분석 기록과 결과 파일을 삭제할까요?")) return;
    try { await api.call(`/api/v1/analyses/${encodeURIComponent(id)}`, { method: "DELETE" }); await refresh(); }
    catch (error) { setMessage(errorText(error)); }
  }

  async function deleteGame(id: string) {
    if (!window.confirm("이 게임 기록을 삭제할까요? 진행 중인 게임도 취소되며 복구할 수 없습니다.")) return;
    try { await api.call(`/api/v1/games/${encodeURIComponent(id)}`, { method: "DELETE" }); await refresh(); }
    catch (error) { setMessage(errorText(error)); }
  }

  return (
    <div>
      <header className="page-head"><div><p className="kicker">SESSION RECORDS</p><h1>분석 및 게임 이력</h1><p className="muted">Bearer 세션 소유 범위 안의 기록만 서버에서 조회합니다.</p></div><button className="secondary small-button" onClick={() => void refresh()}>새로고침</button></header>
      <p className="notice" role="status">{message}</p>
      <div className="grid-2">
        <section className="card"><h2>영상 분석</h2><div className="list">
          {analyses.length === 0 && <div className="empty">저장된 분석이 없습니다.</div>}
          {analyses.map((item) => <article className="list-item" key={item.id}><div><h3>{item.filename}</h3><p className="id">{item.id}</p><span className="status-chip">{item.status}</span></div><div className="button-row"><button onClick={() => router.push(`/analysis/?id=${encodeURIComponent(item.id)}`)}>열기</button><button className="danger" disabled={!(["COMPLETED", "FAILED"].includes(item.status))} onClick={() => void deleteAnalysis(item.id)}>삭제</button></div></article>)}
        </div></section>
        <section className="card"><h2>판별 게임</h2><div className="list">
          {games.length === 0 && <div className="empty">저장된 게임이 없습니다.</div>}
          {games.map((game) => <article className="list-item" key={game.id}><div><h3>{game.total_rounds} 라운드 · {game.difficulty.toUpperCase()}</h3><p className="id">{game.id}</p><span className="status-chip">{game.status} · {game.answered_rounds}/{game.total_rounds}</span></div><div className="button-row"><button onClick={() => router.push(game.status === "COMPLETED" ? `/result/?id=${encodeURIComponent(game.id)}` : `/game/?id=${encodeURIComponent(game.id)}`)}>{game.status === "COMPLETED" ? "결과" : "이어하기"}</button><button className="danger" onClick={() => void deleteGame(game.id)}>삭제</button></div></article>)}
        </div></section>
      </div>
    </div>
  );
}
