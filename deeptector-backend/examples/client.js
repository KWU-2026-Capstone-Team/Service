// Browser ES module. Keep token in memory; reload creates a new anonymous session.
export class DeepTectorClient {
  constructor(base = 'http://127.0.0.1:8000') { this.base = base; this.token = null; }
  async call(path, {method = 'GET', json, body, signal} = {}) {
    const headers = this.token ? {Authorization: `Bearer ${this.token}`} : {};
    if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
    const r = await fetch(this.base + path, {method, headers, body, signal});
    if (r.status === 204) return null;
    const data = await r.json();
    if (!r.ok) throw Object.assign(new Error(data.error?.message || 'Request failed'), {status: r.status, detail: data.error});
    return data;
  }
  async session() { const s = await this.call('/api/v1/sessions', {method:'POST'}); this.token=s.token; return s; }
  async upload(file) {
    const body = new FormData(); body.append('video', file);
    return this.call('/api/v1/analyses', {method:'POST', body});
  }
  async poll(id, onUpdate = () => {}, signal) {
    while (!signal?.aborted) {
      const a = await this.call(`/api/v1/analyses/${id}`, {signal}); onUpdate(a);
      if (['COMPLETED','FAILED'].includes(a.status)) return a;
      await new Promise(r => setTimeout(r, 1500));
    }
    throw new DOMException('Polling stopped', 'AbortError');
  }
  async artifact(url) {
    const r=await fetch(this.base+url,{headers:{Authorization:`Bearer ${this.token}`}});
    if(!r.ok) throw new Error(`Artifact request failed: ${r.status}`);
    // Caller must URL.revokeObjectURL when image/video is no longer used.
    return URL.createObjectURL(await r.blob());
  }
  startGame(rounds=10) { return this.call('/api/v1/games',{method:'POST',json:{rounds}}); }
  round(id,n) { return this.call(`/api/v1/games/${id}/rounds/${n}`); }
  answer(id,n,verdict,confidence) { return this.call(`/api/v1/games/${id}/rounds/${n}/answer`,{method:'POST',json:{verdict,confidence}}); }
  gameResult(id) { return this.call(`/api/v1/games/${id}/result`); }
}
