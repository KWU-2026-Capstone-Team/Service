import {Api, TERMINAL, errorText} from './api.mjs';
const $ = id => document.getElementById(id);
const api = new Api();
let connected = false, selected = null, generation = 0, timer, imageUrls = [], gameUrl;
const gameKey = 'deeptector.game';
const message = text => { $('message').textContent = text; };
function el(tag, text, className) { const item = document.createElement(tag); if (text !== undefined) item.textContent = text; if (className) item.className = className; return item; }
async function action(button, work) { button.disabled = true; message(''); try { await work(); } catch (error) { message(errorText(error)); } finally { button.disabled = false; } }
function clearImages() { imageUrls.forEach(url => URL.revokeObjectURL(url)); imageUrls = []; $('artifacts').replaceChildren(); }
async function history() {
  const data = await api.call('/api/v1/analyses?limit=50');
  $('history').replaceChildren();
  if (!data.items.length) $('history').textContent = '이 세션에 저장된 분석이 없습니다.';
  for (const item of data.items) {
    const b = el('button', `${item.filename} · ${item.status}`, 'secondary');
    b.onclick = () => openAnalysis(item.id); $('history').append(b);
  }
}
async function openAnalysis(id) {
  clearTimeout(timer); const version = ++generation; selected = null;
  clearImages(); $('detail').hidden = false; $('result').replaceChildren(); $('delete').disabled = true;
  $('analysisState').textContent = '상태 조회 중…';
  async function poll() {
    try {
      const item = await api.call(`/api/v1/analyses/${encodeURIComponent(id)}`);
      if (version !== generation) return;
      selected = item; render(item);
      if (!TERMINAL.has(item.status)) timer = setTimeout(poll, 1800);
      else { await history(); await loadArtifacts(item, version); }
    } catch (error) { if (version === generation) { message(errorText(error)); $('analysisState').textContent = '조회 중단 — 이력에서 항목을 다시 눌러 재시도하세요.'; } }
  }
  await poll();
}
function render(item) {
  $('analysisName').textContent = `${item.filename} · ${item.id}`;
  $('analysisState').textContent = `${item.status} / ${item.stage}`;
  $('waiting').hidden = TERMINAL.has(item.status);
  $('delete').disabled = !TERMINAL.has(item.status);
  $('raw').textContent = JSON.stringify(item, null, 2);
  $('result').replaceChildren();
  if (item.error) $('result').append(el('p', `${item.error.code}: ${item.error.message}`, 'notice'));
  if (!item.result) return;
  const r = item.result, p = r.policy;
  if (r.is_demo) $('result').append(el('p', 'DEMO · 아래 점수와 얼굴 수는 고정 시연값입니다. 실제 탐지 결과가 아닙니다.', 'notice'));
  const color = ['RED','YELLOW','GREEN'].includes(p.traffic_light) ? p.traffic_light : '';
  const signal = el('div', undefined, `signal ${color}`);
  signal.append(el('h3', `${p.traffic_light} · ${p.verdict}`), el('div', `${p.signal_score.toFixed(1)} / 100`, 'score'), el('p', '모델 신호 점수 · 조작 확률이나 확정적 증거가 아닙니다.'));
  const metrics = el('div', undefined, 'metrics');
  for (const [name,key] of [['공간','spatial'],['시간','temporal'],['융합','fused']]) metrics.append(el('p', `${name} ${(r[key]*100).toFixed(1)}`));
  signal.append(metrics); $('result').append(signal, el('p', r.faithfulness));
  if (r.warnings?.length) $('result').append(el('p', `주의: ${r.warnings.join(', ')}`));
  if (!item.artifacts.length) $('result').append(el('p', r.is_demo ? 'DEMO에서는 XAI 이미지를 생성하지 않습니다.' : '제공된 XAI 이미지가 없습니다. 경고와 상세 응답을 확인하세요.', 'muted'));
}
async function loadArtifacts(item, version) {
  for (const artifact of item.artifacts) {
    try {
      const blob = await api.call(artifact.url, {blob:true});
      if (version !== generation) return;
      if (!['image/png','image/jpeg','image/gif','image/webp'].includes(blob.type)) continue;
      const url = URL.createObjectURL(blob); imageUrls.push(url);
      const figure = el('figure'), image = el('img'); image.src=url; image.alt=artifact.kind;
      figure.append(image, el('figcaption',artifact.kind)); $('artifacts').append(figure);
    } catch (error) { if(version === generation) message(`이미지 조회 실패: ${errorText(error)}`); }
  }
}
$('connect').onclick = () => action($('connect'), async () => {
  const oldToken = api.token; const model = await api.connect();
  if (oldToken && oldToken !== api.token) { sessionStorage.removeItem(gameKey); ++generation; clearTimeout(timer); clearImages(); $('detail').hidden=true; selected=null; }
  connected=true; $('connection').textContent=`연결됨 · ${model.inference_mode.toUpperCase()}`;
  for (const id of ['upload','refresh','gameStart']) $(id).disabled=false;
  await history(); message('연결되었습니다. 영상을 선택해 분석하거나 이력에서 기존 작업을 확인하세요.');
});
$('video').onchange = () => { const f=$('video').files[0]; $('fileInfo').textContent=f ? `${f.name} · ${(f.size/1024/1024).toFixed(2)} MiB` : '선택한 영상이 없습니다.'; };
$('uploadForm').onsubmit = event => { event.preventDefault(); action($('upload'), async () => {
  if(!connected) throw new Error('먼저 백엔드에 연결하세요.');
  const file=$('video').files[0]; if(!file || !file.size) throw new Error('비어 있지 않은 영상 파일을 선택하세요.');
  if(file.size>50*1024*1024) throw new Error('로컬 테스트는 50MiB 이하 영상을 사용하세요.');
  message('업로드 중… 중복 업로드하지 말고 기다려 주세요.');
  const item=await api.upload(file); message('업로드 완료. 서버의 분석 상태를 확인합니다.');
  await history(); await openAnalysis(item.id);
}); };
$('refresh').onclick = () => action($('refresh'),history);
$('delete').onclick = async () => {
  if(!selected || !TERMINAL.has(selected.status) || !confirm('이 분석의 기록과 결과 파일을 삭제할까요? 복구할 수 없습니다.')) return;
  const id=selected.id;
  await action($('delete'),async () => {
    await api.call(`/api/v1/analyses/${id}`,{method:'DELETE'});
    if(selected?.id===id) { ++generation; clearTimeout(timer); clearImages(); selected=null; $('detail').hidden=true; }
    await history(); message('분석 기록과 결과 파일을 삭제했습니다.');
  });
};
async function gameRound(game) {
  const body=$('gameBody'); body.replaceChildren();
  if(gameUrl) { URL.revokeObjectURL(gameUrl); gameUrl=null; }
  if(game.status==='COMPLETED') {
    const result=await api.call(`/api/v1/games/${game.id}/result`);
    body.append(el('h3',`결과 · 나 ${result.user_score}/${result.total_rounds} · AI ${result.ai_score}/${result.total_rounds}`),el('pre',JSON.stringify(result.categories,null,2)));
    sessionStorage.removeItem(gameKey); return;
  }
  const round=await api.call(`/api/v1/games/${game.id}/rounds/${game.next_round}`);
  body.append(el('h3',`${game.next_round} / ${game.total_rounds} 문제`));
  const video=el('video'); video.controls=true; video.playsInline=true; body.append(video);
  gameUrl=URL.createObjectURL(await api.call(round.video_url,{blob:true})); video.src=gameUrl;
  const form=el('form'), label=el('label','확신도 '), confidence=el('select');
  for(const [value,text] of [['LOW','낮음'],['MEDIUM','보통'],['HIGH','높음']]) { const o=el('option',text); o.value=value; confidence.append(o); }
  label.append(confidence); form.append(label);
  for(const verdict of ['REAL','FAKE']) { const b=el('button',verdict); b.type='submit'; b.value=verdict; form.append(b); }
  form.onsubmit=async event => {
    event.preventDefault(); const buttons=[...form.querySelectorAll('button')]; buttons.forEach(b=>b.disabled=true);
    try {
      const result=await api.call(`/api/v1/games/${game.id}/rounds/${game.next_round}/answer`,{method:'POST',json:{verdict:event.submitter.value,confidence:confidence.value}});
      body.append(el('p',`정답 ${result.ground_truth} · ${result.user_correct?'맞았습니다':'틀렸습니다'} · AI ${result.ai.verdict}`));
      const next=el('button','다음 / 최종 결과'); body.append(next); next.onclick=()=>action(next,async()=>gameRound(await api.call(`/api/v1/games/${game.id}`)));
    } catch(error) { message(errorText(error)); buttons.forEach(b=>b.disabled=false); }
  };
  body.append(form);
}
$('gameStart').onclick=()=>action($('gameStart'),async()=>{
  let id=sessionStorage.getItem(gameKey), game;
  if(id) { try { game=await api.call(`/api/v1/games/${id}`); } catch(error) { if(error.status!==404) throw error; sessionStorage.removeItem(gameKey); } }
  if(!game) { const rounds=Number($('rounds').value); if(!Number.isInteger(rounds)||rounds<1||rounds>10) throw new Error('문제 수는 1~10입니다.'); game=await api.call('/api/v1/games',{method:'POST',json:{rounds}}); sessionStorage.setItem(gameKey,game.id); }
  await gameRound(game);
});
window.addEventListener('pagehide',()=>{ clearTimeout(timer); clearImages(); if(gameUrl) URL.revokeObjectURL(gameUrl); });
