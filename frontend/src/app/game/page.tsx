"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ACTIVE_GAME_KEY, ApiError, api, errorText } from "@/lib/api";
import type { AnswerReveal, Confidence, Difficulty, GameRound, GameSummary, Verdict } from "@/lib/types";

type Phase = "loading" | "watching" | "revealing" | "error";

function GameContent() {
  const router = useRouter();
  const params = useSearchParams();
  const [game, setGame] = useState<GameSummary | null>(null);
  const [round, setRound] = useState<GameRound | null>(null);
  const [reveal, setReveal] = useState<AnswerReveal | null>(null);
  const [phase, setPhase] = useState<Phase>("loading");
  const [message, setMessage] = useState("게임 세션을 확인하는 중입니다…");
  const [confidence, setConfidence] = useState<Confidence>("MEDIUM");
  const [submitting, setSubmitting] = useState<Verdict | null>(null);
  const [videoUrl, setVideoUrl] = useState("");
  const startedAt = useRef(0);
  const initialized = useRef(false);
  const busy = useRef(false);
  const objectUrl = useRef("");
  const mounted = useRef(true);

  const releaseVideo = useCallback(() => {
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = "";
    setVideoUrl("");
  }, []);

  const loadRound = useCallback(async (state: GameSummary) => {
    releaseVideo();
    setReveal(null);
    if (state.status === "COMPLETED" || state.next_round == null) {
      sessionStorage.removeItem(ACTIVE_GAME_KEY);
      router.replace(`/result/?id=${encodeURIComponent(state.id)}`);
      return;
    }
    setPhase("loading");
    const current = await api.round(state.id, state.next_round);
    if (!mounted.current) return;
    setRound(current);
    if (current.answered && current.result) {
      setReveal(current.result);
      setPhase("revealing");
      return;
    }
    const blob = await api.call<Blob>(current.video_url, { blob: true });
    if (!mounted.current) return;
    if (!blob.type.startsWith("video/")) throw new Error("서버 응답이 재생 가능한 영상 형식이 아닙니다.");
    objectUrl.current = URL.createObjectURL(blob);
    setVideoUrl(objectUrl.current);
    startedAt.current = performance.now();
    setPhase("watching");
    setMessage("영상을 관찰한 뒤 REAL 또는 FAKE를 선택하세요.");
  }, [releaseVideo, router]);

  const refreshGame = useCallback(async (id: string) => {
    const state = await api.game(id);
    setGame(state);
    await loadRound(state);
  }, [loadRound]);

  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;
    const start = async () => {
      try {
        await api.connect();
        if (!mounted.current) return;
        let id = params.get("id") || (params.has("rounds") ? "" : sessionStorage.getItem(ACTIVE_GAME_KEY)) || "";
        if (id) {
          try {
            const existing = await api.game(id);
            if (!mounted.current) return;
            setGame(existing);
            if (existing.status === "COMPLETED") {
              sessionStorage.removeItem(ACTIVE_GAME_KEY);
              return router.replace(`/result/?id=${encodeURIComponent(id)}`);
            }
            sessionStorage.setItem(ACTIVE_GAME_KEY, id);
            router.replace(`/game/?id=${encodeURIComponent(id)}`);
            await loadRound(existing);
            return;
          } catch (error) {
            if (!(error instanceof ApiError) || error.status !== 404) throw error;
            sessionStorage.removeItem(ACTIVE_GAME_KEY);
            id = "";
          }
        }
        const requested = Number(params.get("rounds") || 10);
        const rounds = [5, 10, 15].includes(requested) ? requested : 10;
        const requestedDifficulty = params.get("difficulty");
        const difficulty: Difficulty = ["all", "easy", "medium", "hard"].includes(requestedDifficulty || "") ? requestedDifficulty as Difficulty : "all";
        const created = await api.call<GameSummary>("/api/v1/games", { method: "POST", json: { rounds, difficulty } });
        sessionStorage.setItem(ACTIVE_GAME_KEY, created.id);
        const state = await api.game(created.id);
        if (!mounted.current) return;
        setGame(state);
        router.replace(`/game/?id=${encodeURIComponent(created.id)}`);
        await loadRound(state);
      } catch (error) {
        setPhase("error");
        setMessage(errorText(error));
      }
    };
    void start();
  }, [loadRound, params, releaseVideo, router]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    };
  }, []);

  const answer = useCallback(async (verdict: Verdict) => {
    if (!game || !round || phase !== "watching" || busy.current) return;
    busy.current = true;
    setSubmitting(verdict);
    try {
      const timeUsed = Math.max(0, (performance.now() - startedAt.current) / 1000);
      const result = await api.call<AnswerReveal>(`/api/v1/games/${encodeURIComponent(game.id)}/rounds/${round.round}/answer`, {
        method: "POST",
        json: { verdict, confidence, time_used_seconds: Number(timeUsed.toFixed(2)) },
      });
      setReveal(result);
      setPhase("revealing");
      setMessage(result.user_correct ? `정답입니다. +${result.points}점` : "오답입니다. 해설과 단서를 확인하세요.");
      setGame(await api.game(game.id));
    } catch (error) { setMessage(errorText(error)); }
    finally { busy.current = false; setSubmitting(null); }
  }, [confidence, game, phase, round]);

  const next = useCallback(async () => {
    if (!game || phase !== "revealing" || busy.current) return;
    busy.current = true;
    setPhase("loading");
    setMessage("다음 라운드를 불러오는 중입니다…");
    try { await refreshGame(game.id); }
    catch (error) { setPhase("error"); setMessage(errorText(error)); }
    finally { busy.current = false; }
  }, [game, phase, refreshGame]);

  const skip = useCallback(async () => {
    if (!game || !round || phase === "revealing" || busy.current) return;
    busy.current = true;
    setPhase("loading");
    setMessage("재생할 수 없는 영상을 서버에 기록하고 건너뜁니다…");
    try {
      await api.call(`/api/v1/games/${encodeURIComponent(game.id)}/rounds/${round.round}/skip`, { method: "POST", json: { reason: "VIDEO_UNAVAILABLE" } });
      await refreshGame(game.id);
    } catch (error) { setPhase("error"); setMessage(errorText(error)); }
    finally { busy.current = false; }
  }, [game, phase, refreshGame, round]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.matches("input, select, textarea, button")) return;
      if (event.key.toLowerCase() === "r") void answer("REAL");
      if (event.key.toLowerCase() === "f") void answer("FAKE");
      if (event.code === "Space" && phase === "revealing") { event.preventDefault(); void next(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [answer, next, phase]);

  return (
    <div className="game-page">
      <div className="game-topbar">
        <button className="ghost" onClick={() => router.push("/")}>← 종료</button>
        <div><span>ROUND</span><b>{round?.round || 0} / {game?.total_rounds || "—"}</b></div>
        <div><span>SCORE</span><b>{game?.score ?? 0}</b></div>
        <div><span>MAX COMBO</span><b>×{game?.max_streak ?? 0}</b></div>
      </div>

      <div className="game-stage">
        <div className="video-shell">
          {videoUrl && <video src={videoUrl} controls playsInline autoPlay onError={() => { setPhase("error"); setMessage("영상을 재생할 수 없습니다. 다시 시도하거나 건너뛰세요."); }} onLoadedData={() => { startedAt.current = performance.now(); }} />}
          {phase === "loading" && <div className="video-placeholder"><span className="spinner" /><p>SECURE VIDEO STREAM</p></div>}
          {phase === "error" && <div className="video-placeholder error"><b>ROUND UNAVAILABLE</b><p>{message}</p><button className="secondary" onClick={() => { if (game) void refreshGame(game.id).catch(error => { setPhase("error"); setMessage(errorText(error)); }); else window.location.reload(); }}>다시 시도</button></div>}
          <div className="scan-overlay" aria-hidden="true" />
        </div>

        <aside className="game-panel">
          <p className="kicker">EVIDENCE REVIEW</p>
          <p className="notice" role="status" aria-live="polite">{message}</p>
          {phase !== "revealing" && <>
            <div className="field"><label htmlFor="confidence">판단 확신도</label><select id="confidence" value={confidence} onChange={(event) => setConfidence(event.target.value as Confidence)}><option value="LOW">낮음</option><option value="MEDIUM">보통</option><option value="HIGH">높음</option></select></div>
            <p className="muted game-help">정답 제출 전에는 라벨, 해설, AI 점수를 가져오지 않습니다. 영상 오류는 오답으로 만들지 않고 서버에 skip으로 기록합니다.</p>
            <button className="small-button secondary" onClick={() => void skip()} disabled={!round || (phase !== "watching" && phase !== "error")}>영상 재생 불가 · 건너뛰기</button>
          </>}
          {reveal && <div className={`reveal ${reveal.user_correct ? "correct" : "wrong"}`}>
            <div className="reveal-title"><strong>{reveal.user_correct ? "CONFIRMED" : "INCORRECT"}</strong><span>{reveal.points > 0 ? `+${reveal.points}` : "0"} PTS · COMBO ×{reveal.streak}</span></div>
            <dl><div><dt>내 답</dt><dd>{reveal.answer}</dd></div><div><dt>정답</dt><dd>{reveal.ground_truth}</dd></div><div><dt>AI</dt><dd>{reveal.ai ? `${reveal.ai.verdict || "평가 있음"}${reveal.ai_correct == null ? "" : reveal.ai_correct ? " · 정답" : " · 오답"}` : "평가 없음"}</dd></div></dl>
            {reveal.technique && <span className="status-chip">{reveal.technique}</span>}
            <p>{reveal.explanation}</p>
            {reveal.cues.length > 0 && <div className="tags">{reveal.cues.map((cue) => <span key={cue}>{cue}</span>)}</div>}
            {reveal.tips.length > 0 && <p className="muted"><b>확인 요령</b> · {reveal.tips.join(" · ")}</p>}
            <button className="wide" onClick={() => void next()}>다음 라운드 <small>[SPACE]</small></button>
          </div>}
        </aside>
      </div>

      <div className="verdict-zone">
        <button onClick={() => void answer("REAL")} disabled={phase !== "watching" || !!submitting}><strong>REAL</strong><span>AUTHENTIC · [R]</span></button>
        <button onClick={() => void answer("FAKE")} disabled={phase !== "watching" || !!submitting}><strong>FAKE</strong><span>MANIPULATED · [F]</span></button>
      </div>
    </div>
  );
}

export default function GamePage() {
  return <Suspense fallback={<div className="video-placeholder"><span className="spinner" /> INITIALIZING</div>}><GameContent /></Suspense>;
}
