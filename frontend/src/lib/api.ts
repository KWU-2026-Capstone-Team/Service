import type { AnalysisDetail, AnalysisSummary, GameResult, GameRound, GameSummary } from "./types";

const SESSION_KEY = "deeptector.session:v1";
export const ACTIVE_GAME_KEY = "deeptector.active-game:v1";
export const TERMINAL_ANALYSIS = new Set(["COMPLETED", "FAILED"]);

export class ApiError extends Error {
  status: number;
  code?: string;
  details?: Record<string, unknown>;

  constructor(message: string, status: number, code?: string, details?: Record<string, unknown>) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

class ApiClient {
  token = "";
  private connection: Promise<{ model: { inference_mode: string }; reused: boolean }> | null = null;

  private loadToken() {
    if (typeof window !== "undefined" && !this.token) this.token = sessionStorage.getItem(SESSION_KEY) || "";
  }

  async call<T>(path: string, options: { method?: string; body?: BodyInit; json?: unknown; blob?: boolean } = {}): Promise<T> {
    if (!path.startsWith("/api/v1/") && !path.startsWith("/health/")) throw new Error("허용되지 않은 API 경로입니다.");
    this.loadToken();
    const headers: Record<string, string> = this.token ? { Authorization: `Bearer ${this.token}` } : {};
    let body = options.body;
    if (options.json !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(options.json);
    }
    const controller = new AbortController();
    const timeout = body instanceof FormData ? 180_000 : 25_000;
    const timer = window.setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(path, {
        method: options.method || "GET",
        body,
        headers,
        signal: controller.signal,
        credentials: "same-origin",
        cache: "no-store",
      });
      if (response.status === 204) return null as T;
      if (options.blob && response.ok) return (await response.blob()) as T;
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = payload.error || {};
        throw new ApiError(error.message || `HTTP ${response.status}`, response.status, error.code, error.details);
      }
      return payload as T;
    } finally {
      window.clearTimeout(timer);
    }
  }

  connect() {
    if (!this.connection) this.connection = this.performConnect().then(result => {
      window.dispatchEvent(new CustomEvent("deeptector:status", { detail: result.model.inference_mode }));
      return result;
    }).finally(() => { this.connection = null; });
    return this.connection;
  }

  private async performConnect() {
    await this.call("/health/ready");
    const model = await this.call<{ inference_mode: string }>("/api/v1/model-info");
    this.loadToken();
    if (this.token) {
      try {
        await this.call("/api/v1/analyses?limit=1");
        return { model, reused: true };
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) throw error;
        this.token = "";
        sessionStorage.removeItem(SESSION_KEY);
        sessionStorage.removeItem(ACTIVE_GAME_KEY);
      }
    }
    const session = await this.call<{ token: string }>("/api/v1/sessions", { method: "POST" });
    this.token = session.token;
    sessionStorage.setItem(SESSION_KEY, session.token);
    return { model, reused: false };
  }

  analyses() { return this.call<{ items: AnalysisSummary[] }>("/api/v1/analyses?limit=50"); }
  analysis(id: string) { return this.call<AnalysisDetail>(`/api/v1/analyses/${encodeURIComponent(id)}`); }
  games() { return this.call<{ items: GameSummary[] }>("/api/v1/games"); }
  game(id: string) { return this.call<GameSummary>(`/api/v1/games/${encodeURIComponent(id)}`); }
  round(id: string, n: number) { return this.call<GameRound>(`/api/v1/games/${encodeURIComponent(id)}/rounds/${n}`); }
  result(id: string) { return this.call<GameResult>(`/api/v1/games/${encodeURIComponent(id)}/result`); }
}

export const api = new ApiClient();

export function errorText(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401) return "세션이 만료되었습니다. 다시 연결해 주세요.";
    if (error.code === "GAME_DATASET_NOT_READY") return "게임 데이터가 준비되지 않았습니다. 백엔드에 검증된 영상 데이터가 필요합니다.";
    if (error.code === "ANALYSIS_NOT_TERMINAL") return "분석 완료 또는 실패 후에만 삭제할 수 있습니다.";
    return `${error.code ? `${error.code}: ` : ""}${error.message}`;
  }
  if (error instanceof DOMException && error.name === "AbortError") return "요청 시간이 초과되었습니다. 이력을 새로고침해 서버 상태를 확인하세요.";
  if (error instanceof TypeError) return "로컬 백엔드에 연결하지 못했습니다. 실행 상태와 브라우저의 로컬 네트워크 권한을 확인하세요.";
  return error instanceof Error ? error.message : "알 수 없는 오류가 발생했습니다.";
}
