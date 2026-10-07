export const BASE = 'http://127.0.0.1:8000';
export const TERMINAL = new Set(['COMPLETED', 'FAILED']);
export class Api {
  constructor(storage = sessionStorage, fetcher = fetch) {
    this.storage = storage; this.fetcher = fetcher; this.key = `deeptector.session:${BASE}`;
    this.token = storage.getItem(this.key) || '';
  }
  async call(path, {method = 'GET', body, json, blob = false} = {}) {
    if (!path.startsWith('/api/v1/') && !path.startsWith('/health/')) throw new Error('Invalid API path');
    const headers = this.token ? {Authorization: `Bearer ${this.token}`} : {};
    if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), method === 'POST' && body instanceof FormData ? 180000 : 20000);
    try {
      const response = await this.fetcher(BASE + path, {method, body, headers, signal: controller.signal, credentials: 'omit', cache: 'no-store'});
      if (response.status === 204) return null;
      if (blob && response.ok) return await response.blob();
      const data = await response.json();
      if (!response.ok) throw Object.assign(new Error(data.error?.message || `HTTP ${response.status}`), {status: response.status, code: data.error?.code});
      return data;
    } finally { clearTimeout(timer); }
  }
  async connect() {
    await this.call('/health/ready');
    const model = await this.call('/api/v1/model-info');
    if (this.token) {
      try { await this.call('/api/v1/analyses?limit=1'); return model; }
      catch (error) { if (error.status !== 401) throw error; this.token = ''; this.storage.removeItem(this.key); }
    }
    const session = await this.call('/api/v1/sessions', {method: 'POST'});
    this.storage.setItem(this.key, session.token); this.token = session.token;
    return model;
  }
  upload(file) { const body = new FormData(); body.append('video', file); return this.call('/api/v1/analyses', {method:'POST', body}); }
}
export function errorText(error) {
  if (error.status === 401) return '세션이 만료되었거나 유효하지 않습니다. 백엔드 연결을 다시 눌러 주세요.';
  if (error.code === 'GAME_DATASET_NOT_READY') return '게임 데이터가 아직 등록되지 않았습니다. 정답·출처·사용권이 확인된 영상을 먼저 등록하세요.';
  if (error.code === 'ANALYSIS_NOT_TERMINAL') return '분석이 아직 대기/처리 중입니다. 완료 또는 실패 후 삭제하세요.';
  if (error.name === 'AbortError') return '요청 시간이 초과되었습니다. 업로드 요청은 이미 접수됐을 수 있으니 이력을 먼저 새로고침하세요.';
  if (error instanceof TypeError) return '백엔드에 연결하지 못했습니다. 로컬 실행기, CORS 설정, 브라우저의 이 사이트 로컬 네트워크 접근 권한을 확인하세요.';
  return `${error.code ? error.code + ': ' : ''}${error.message}`;
}
