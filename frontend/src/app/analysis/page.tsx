"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api, errorText, TERMINAL_ANALYSIS } from "@/lib/api";
import type { AnalysisDetail, AnalysisSummary } from "@/lib/types";

function AnalysisContent() {
  const router = useRouter();
  const params = useSearchParams();
  const queryId = params.get("id");
  const [items, setItems] = useState<AnalysisSummary[]>([]);
  const [selected, setSelected] = useState<AnalysisDetail | null>(null);
  const [message, setMessage] = useState("백엔드에 연결한 뒤 영상을 업로드하세요.");
  const [busy, setBusy] = useState(false);
  const [artifactUrls, setArtifactUrls] = useState<{ kind: string; url: string }[]>([]);
  const timer = useRef<number | null>(null);
  const generation = useRef(0);

  const clearArtifacts = useCallback(() => {
    setArtifactUrls((current) => {
      current.forEach((item) => URL.revokeObjectURL(item.url));
      return [];
    });
  }, []);

  const loadHistory = useCallback(async () => {
    const data = await api.analyses();
    setItems(data.items);
  }, []);

  const loadArtifacts = useCallback(async (item: AnalysisDetail, version: number) => {
    const next: { kind: string; url: string }[] = [];
    for (const artifact of item.artifacts) {
      try {
        const blob = await api.call<Blob>(artifact.url, { blob: true });
        if (generation.current !== version) return next.forEach((entry) => URL.revokeObjectURL(entry.url));
        if (blob.type.startsWith("image/")) next.push({ kind: artifact.kind, url: URL.createObjectURL(blob) });
      } catch (error) {
        if (generation.current === version) setMessage(`XAI 이미지 조회 실패: ${errorText(error)}`);
      }
    }
    if (generation.current === version) setArtifactUrls(next);
  }, []);

  const openAnalysis = useCallback(async (id: string) => {
    if (timer.current) window.clearTimeout(timer.current);
    const version = ++generation.current;
    clearArtifacts();
    setSelected(null);
    setMessage("분석 상태를 조회하는 중입니다…");
    let failures = 0;
    const poll = async () => {
      try {
        const item = await api.analysis(id);
        if (generation.current !== version) return;
        failures = 0;
        setSelected(item);
        setMessage(TERMINAL_ANALYSIS.has(item.status) ? "서버의 최종 분석 상태입니다." : `${item.stage} 단계 처리 중…`);
        if (TERMINAL_ANALYSIS.has(item.status)) {
          await loadHistory();
          await loadArtifacts(item, version);
        } else {
          timer.current = window.setTimeout(poll, 1800);
        }
      } catch (error) {
        if (generation.current === version) {
          setMessage(errorText(error));
          failures += 1;
          if (failures <= 5) timer.current = window.setTimeout(poll, Math.min(1000 * 2 ** failures, 15000));
        }
      }
    };
    await poll();
  }, [clearArtifacts, loadArtifacts, loadHistory]);

  useEffect(() => {
    const connectAndLoad = async () => {
      try {
        await api.connect();
        await loadHistory();
        if (queryId) await openAnalysis(queryId);
        else setMessage("연결되었습니다. 50 MiB 이하 영상을 선택하세요.");
      } catch (error) { setMessage(errorText(error)); }
    };
    void connectAndLoad();
    return () => {
      generation.current += 1;
      if (timer.current) window.clearTimeout(timer.current);
      clearArtifacts();
    };
  }, [clearArtifacts, loadHistory, openAnalysis, queryId]);

  async function upload(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const input = form.elements.namedItem("video") as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !file.size) return setMessage("비어 있지 않은 영상 파일을 선택하세요.");
    if (file.size > 50 * 1024 * 1024) return setMessage("로컬 테스트 업로드 한도는 50 MiB입니다.");
    setBusy(true);
    setMessage("업로드 중입니다. 중복 제출하지 말고 기다려 주세요…");
    try {
      await api.connect();
      const body = new FormData();
      body.append("video", file);
      const item = await api.call<AnalysisSummary>("/api/v1/analyses", { method: "POST", body });
      router.replace(`/analysis/?id=${encodeURIComponent(item.id)}`);
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  async function remove() {
    if (!selected || !TERMINAL_ANALYSIS.has(selected.status) || !window.confirm("이 분석 기록과 결과 파일을 삭제할까요? 복구할 수 없습니다.")) return;
    setBusy(true);
    try {
      await api.call(`/api/v1/analyses/${encodeURIComponent(selected.id)}`, { method: "DELETE" });
      generation.current += 1;
      clearArtifacts();
      setSelected(null);
      await loadHistory();
      router.replace("/analysis/");
      setMessage("분석 기록과 결과 파일을 삭제했습니다.");
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  const result = selected?.result;
  const policy = result?.policy;

  return (
    <div>
      <header className="page-head">
        <div><p className="kicker">VIDEO ANALYSIS</p><h1>영상 포렌식 분석</h1><p className="muted">실제 서버 상태와 모델 신호, 제공된 XAI artifact만 표시합니다.</p></div>
        {selected && <span className="status-chip">{selected.status} · {selected.stage}</span>}
      </header>
      <p className={`notice ${message.includes("실패") || message.includes("못했습니다") ? "error" : ""}`} role="status" aria-live="polite">{message}</p>

      <div className="grid-2">
        <section className="card">
          <h2>분석 요청</h2>
          <form onSubmit={upload} className="field">
            <label htmlFor="video">MP4, MOV, AVI, MKV · 최대 50 MiB</label>
            <input id="video" name="video" type="file" accept="video/mp4,video/quicktime,video/x-msvideo,video/x-matroska" />
            <button className="primary" disabled={busy} type="submit">{busy ? "처리 중…" : "업로드 및 분석"}</button>
          </form>
          <h3 style={{ marginTop: 28 }}>최근 분석</h3>
          <div className="list">
            {items.length === 0 && <div className="empty">이 세션에 저장된 분석이 없습니다.</div>}
            {items.slice(0, 8).map((item) => (
              <button key={item.id} className="list-item" onClick={() => router.push(`/analysis/?id=${encodeURIComponent(item.id)}`)}>
                <span style={{ textAlign: "left" }}><b>{item.filename}</b><span className="id" style={{ display: "block" }}>{item.id}</span></span>
                <span className="status-chip">{item.status}</span>
              </button>
            ))}
          </div>
        </section>

        <section className="card">
          {!selected && <div className="empty">분석 항목을 선택하면 진행 상태와 결과가 여기에 표시됩니다.</div>}
          {selected && <>
            <div className="button-row" style={{ justifyContent: "space-between" }}>
              <div><h2>{selected.filename}</h2><p className="id">{selected.id}</p></div>
              <button className="secondary" onClick={() => void openAnalysis(selected.id)}>상태 다시 확인</button>
              <button className="danger" disabled={busy || !TERMINAL_ANALYSIS.has(selected.status)} onClick={remove}>삭제</button>
            </div>
            {!TERMINAL_ANALYSIS.has(selected.status) && <div style={{ display: "flex", gap: 12, alignItems: "center", marginTop: 28 }}><span className="spinner" /><span>{selected.stage}</span></div>}
            {selected.error && <div className="notice error"><b>{selected.error.code || "ANALYSIS_FAILED"}</b><br />{selected.error.message || "분석에 실패했습니다."}</div>}
            {result && policy && <>
              {result.is_demo && <p className="notice">DEMO · 아래 값은 시연 응답이며 실제 탐지 결과가 아닙니다.</p>}
              <div className={`traffic ${policy.traffic_light}`}>
                <p className="kicker">{policy.traffic_light} · {policy.verdict}</p>
                <div className="traffic-score">{policy.signal_score.toFixed(1)}<small> / 100</small></div>
                <p className="muted">모델 신호 점수이며 조작 확률이나 확정적 증거가 아닙니다.</p>
              </div>
              <div className="metric-grid" style={{ marginTop: 16 }}>
                {["spatial", "temporal", "fused"].map((key) => <div className="metric" key={key}><b>{(result[key as "spatial" | "temporal" | "fused"] * 100).toFixed(1)}</b><span>{key.toUpperCase()}</span></div>)}
                <div className="metric"><b>{result.n_faces}</b><span>FACE SAMPLES</span></div>
              </div>
              <p className="muted">{result.faithfulness}</p>
              <details><summary>모델 및 판단 기준</summary><p className="id">{result.model_version} · {policy.policy_version}</p><p>주요 신호: {policy.primary_signal} · 기준: {policy.reason}</p><p className="muted">{JSON.stringify(policy.thresholds)}</p></details>
              {result.warnings.length > 0 && <p className="notice">주의: {result.warnings.join(" · ")}</p>}
              {artifactUrls.length === 0 && <div className="empty">서버가 제공한 XAI 이미지가 없습니다.</div>}
              <div className="artifact-grid">{artifactUrls.map((item) => <figure key={item.kind}><img src={item.url} alt={`${item.kind} XAI 시각화`} /><figcaption>{item.kind}</figcaption></figure>)}</div>
            </>}
          </>}
        </section>
      </div>
    </div>
  );
}

export default function AnalysisPage() {
  return <Suspense fallback={<div className="empty">분석 화면을 불러오는 중…</div>}><AnalysisContent /></Suspense>;
}
