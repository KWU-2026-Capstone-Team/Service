const app = document.querySelector('#app');
const easyModeInput = document.querySelector('#easyMode');
const toast = document.querySelector('#toast');

const mockResults = {
  HIGH: {
    riskLevel: 'HIGH', overallScore: .87, mouthScore: .82, eyeScore: .71, temporalScore: .89,
    title: '다시 확인이 필요해요',
    description: '영상 속 얼굴 움직임에서 조작이 의심되는 부분이 발견됐어요.',
    easyDescription: '영상 속 얼굴 움직임이 자연스럽지 않은 부분이 있어요.',
    reasonText: '입과 얼굴 움직임이 자연스럽게 이어지지 않는 부분이 있어요.',
    suspiciousSegments: [{ start: 8, end: 12 }, { start: 21, end: 24 }]
  },
  MEDIUM: {
    riskLevel: 'MEDIUM', overallScore: .58, mouthScore: .61, eyeScore: .48, temporalScore: .64,
    title: '한 번 더 확인해주세요',
    description: 'AI가 판단하기 어려운 부분이 있어 한 번 더 확인이 필요해요.',
    easyDescription: '영상 일부를 한 번 더 확인해주세요.',
    reasonText: '영상 일부에서 얼굴 움직임이 일정하지 않게 나타났어요.',
    suspiciousSegments: [{ start: 15, end: 18 }]
  },
  LOW: {
    riskLevel: 'LOW', overallScore: .18, mouthScore: .22, eyeScore: .14, temporalScore: .19,
    title: '큰 조작 신호는 발견되지 않았어요',
    description: '현재 분석에서는 뚜렷한 조작 신호가 발견되지 않았어요.',
    easyDescription: '뚜렷하게 의심되는 부분은 찾지 못했어요.',
    reasonText: '얼굴과 입 움직임에서 뚜렷한 이상 신호가 나타나지 않았어요.',
    suspiciousSegments: []
  }
};

const records = [
  { name: 'video_01.mp4', date: '2026-09-15', time: '2분 전', level: 'HIGH', type: 'video' },
  { name: 'family_call.mp4', date: '2026-09-15', time: '오늘 오전 10:24', level: 'MEDIUM', type: 'video' },
  { name: 'video_02.mp4', date: '2026-09-14', time: '어제 오후 4:18', level: 'LOW', type: 'video' },
  { name: 'profile_photo.jpg', date: '2026-09-12', time: '9월 12일', level: 'LOW', type: 'photo' }
];

const previewParams = new URLSearchParams(window.location.search);
const previewRisk = previewParams.get('risk');
const previewScreen = previewParams.get('screen');
const previewRoutes = ['home', 'captureGuide', 'captureReview', 'analyzing', 'result'];
const state = {
  route: previewRoutes.includes(previewScreen) ? previewScreen : 'home',
  result: mockResults[previewRisk] || mockResults.HIGH,
  easy: previewParams.get('easy') === '1',
  analysisTimer: null,
  filter: 'ALL',
  showDevTools: previewParams.get('dev') === '1',
  capturedPath: '',
  capturedName: 'video_capture_001.mp4',
  capturedDurationMs: 24000
};

const iconPaths = {
  home: '<path d="M3 10.8 12 3l9 7.8"/><path d="M5.7 9.5V21h12.6V9.5M9.5 21v-6h5v6"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.6 9a2.6 2.6 0 1 1 3.2 2.5c-.8.3-.8 1-.8 1.5M12 17h.01"/>',
  video: '<path d="m15 10 4.5-2.5v9L15 14"/><rect x="3" y="6" width="12" height="12" rx="2"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9" r="1.5"/><path d="m4 17 5-5 3 3 2-2 6 5"/>',
  camera: '<path d="M5 7h3l1.5-2h5L16 7h3a2 2 0 0 1 2 2v9H3V9a2 2 0 0 1 2-2Z"/><circle cx="12" cy="12.5" r="3.2"/>',
  shield: '<path d="M12 2 4.5 5v5.8c0 4.7 3.1 9 7.5 10.2 4.4-1.2 7.5-5.5 7.5-10.2V5L12 2Z"/><path d="m8.8 11.8 2.1 2.1 4.5-4.7"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
  back: '<path d="m15 18-6-6 6-6"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  alert: '<path d="M12 3 2.8 19h18.4L12 3Z"/><path d="M12 9v4M12 16.5h.01"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1.1 1"/><path d="M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1.1-1"/>',
  volume: '<path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  play: '<circle cx="12" cy="12" r="9"/><path d="m10 9 5 3-5 3V9Z"/>',
  eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="2.5"/>'
  ,phone: '<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M10 5h4M11 18h2"/>',
  record: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/>',
  stop: '<circle cx="12" cy="12" r="9"/><rect x="9" y="9" width="6" height="6" rx="1"/>'
};

function icon(name) { return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${iconPaths[name] || ''}</svg>`; }
function levelLabel(level) { return { HIGH: '다시 확인 필요', MEDIUM: '추가 확인 필요', LOW: '큰 조작 신호 없음' }[level]; }
function signalLabel(level) { return { HIGH: '확인 필요', MEDIUM: '주의', LOW: '신호 낮음' }[level]; }
function levelClass(level) { return `risk-${level.toLowerCase()}`; }
function pct(value) { return `${Math.round(value * 100)}%`; }
function formatTime(seconds) { return `00:${String(seconds).padStart(2, '0')}`; }

function mediaThumb(type = 'video') {
  return `<span class="thumbnail ${type === 'photo' ? 'photo' : ''}">${icon(type === 'photo' ? 'image' : 'video')}</span>`;
}

function recordCard(record) {
  return `<button class="history-card" data-open-result="${record.level}" aria-label="${record.name}, ${levelLabel(record.level)} 결과 보기">
    ${mediaThumb(record.type)}
    <span class="record-info">
      <span class="filename">${record.name}</span>
      <span class="record-meta">${record.time}</span>
      <span class="risk-pill ${levelClass(record.level)}">${levelLabel(record.level)}</span>
    </span>
    <span class="chevron">${icon('chevron')}</span>
  </button>`;
}

function homeView() {
  return `<section class="screen">
    <div class="hero">
      <p class="eyebrow">AI 영상 검증 도우미</p>
      <h1>영상이 믿을 만한지<br>확인해보세요</h1>
      <p class="hero-copy">다른 앱의 영상을 화면 녹화한 뒤<br class="technical-only"> 조작 의심 신호를 알기 쉽게 확인해보세요.</p>
    </div>
    <article class="card screen-analysis-card">
      <span class="screen-analysis-icon">${icon('phone')}</span>
      <div><h2>화면 속 영상을 확인할까요?</h2><p>다른 앱에서 재생되는 영상을 녹화해 분석할 수 있어요.</p></div>
      <button class="primary-action" data-start-screen-analysis>${icon('record')}화면 분석 시작</button>
    </article>
    <div class="section-head secondary-head"><h2>저장된 파일 분석</h2></div>
    <div class="action-grid">
      <button class="secondary-action" data-analyze="video">${icon('video')}저장된 영상 선택</button>
      <button class="secondary-action" data-analyze="photo">${icon('image')}저장된 사진 선택</button>
    </div>
    <p class="privacy-note">${icon('lock')}<span>선택한 파일은 이 화면의 시연용으로만 사용됩니다.</span></p>
    <div class="section-head"><h2>최근 분석</h2><button class="small-link" data-route="history">전체 보기</button></div>
    <div class="recent-list">${records.slice(0, 2).map(recordCard).join('')}</div>
  </section>`;
}

function captureGuideView() {
  return `<section class="screen">
    <div class="back-row"><button class="back-button" data-route="home" aria-label="홈으로 돌아가기">${icon('back')}</button></div>
    <div class="capture-guide-hero">
      <div class="capture-visual" aria-hidden="true">
        <span class="capture-phone">${icon('phone')}</span>
        <span class="capture-bubble">V</span>
      </div>
      <p class="eyebrow">화면 녹화 안내</p>
      <h1>분석할 영상을<br>화면에 녹화할게요</h1>
      <p>다른 앱에서 영상을 연 뒤, 화면 오른쪽의 VeriVid 버튼을 눌러 녹화를 시작하세요.</p>
    </div>
    <article class="card capture-steps">
      <div><span>1</span><p><strong>화면 녹화 권한 허용</strong><small>Android가 보여주는 안내에서 허용해주세요.</small></p></div>
      <div><span>2</span><p><strong>다른 앱에서 영상 열기</strong><small>확인하려는 영상을 재생할 준비를 해주세요.</small></p></div>
      <div><span>3</span><p><strong>VeriVid 버튼으로 녹화</strong><small>녹화 시작과 중지를 플로팅 버튼에서 선택해요.</small></p></div>
    </article>
    <button class="primary-action capture-ready-button" data-prepare-capture>${icon('record')}화면 녹화 준비</button>
    <p class="privacy-note">${icon('lock')}<span>녹화 영상은 이 기기에만 저장되며 서버로 전송하지 않습니다.</span></p>
  </section>`;
}

function captureReviewView() {
  return `<section class="screen">
    <div class="back-row"><button class="back-button" data-route="home" aria-label="홈으로 돌아가기">${icon('back')}</button></div>
    <div class="page-heading capture-complete-heading">
      <span class="complete-mark">${icon('check')}</span>
      <h1>녹화가 완료됐어요</h1>
      <p>영상을 확인한 뒤 분석을 시작해주세요.</p>
    </div>
    <article class="card capture-review-card">
      <div class="captured-preview"><span>${icon('play')}</span><small>화면 녹화 영상</small></div>
      <div class="captured-file-info">
        <div><p class="filename">${state.capturedName}</p><p class="record-meta">길이: ${formatCapturedDuration(state.capturedDurationMs)}</p></div>
        <span class="capture-ready-pill">분석 준비 완료</span>
      </div>
      <div class="button-stack">
        <button class="secondary-action" data-play-capture>${icon('play')}영상 다시 보기</button>
        <button class="primary-action" data-analyze-capture>${icon('search')}이 영상 분석하기</button>
        <button class="text-action" data-recapture>${icon('record')}다시 녹화하기</button>
      </div>
    </article>
  </section>`;
}

function analyzingView() {
  const analyzingName = state.capturedPath ? state.capturedName : 'video_selected.mp4';
  return `<section class="screen analysis-wrap">
    <div class="back-row"><button class="back-button" data-route="home" aria-label="분석 취소하고 뒤로">${icon('back')}</button></div>
    <div class="media-preview"><span class="scan-line"></span><span class="preview-file">${analyzingName}</span></div>
    <h1>영상을 분석하고 있어요</h1>
    <p class="analysis-sub">잠시만 기다려주세요.<br>얼굴의 움직임을 꼼꼼히 살펴보고 있어요.</p>
    <div class="progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><div class="progress-bar" id="progressBar"></div></div>
    <div class="progress-meta"><span id="progressStatus">얼굴 영역 확인 중</span><strong id="progressText">0%</strong></div>
    <div class="status-steps card">
      <div class="status-step current" data-step="0"><span class="step-dot">1</span><span>얼굴 영역 확인 중</span></div>
      <div class="status-step" data-step="1"><span class="step-dot">2</span><span>의심 구간 분석 중</span></div>
      <div class="status-step" data-step="2"><span class="step-dot">3</span><span>결과 정리 중</span></div>
    </div>
  </section>`;
}

function resultView() {
  const r = state.result;
  const type = r.riskLevel.toLowerCase();
  const description = state.easy ? r.easyDescription : r.description;
  const firstSegment = r.suspiciousSegments[0];
  const isHigh = r.riskLevel === 'HIGH';
  const isMedium = r.riskLevel === 'MEDIUM';
  const isLow = r.riskLevel === 'LOW';

  let reasonCard = '';
  if (!isLow) {
    reasonCard = `<article class="card result-card">
      <div class="card-heading"><span class="heading-icon">${icon('search')}</span><h2>왜 의심스러운가요?</h2></div>
      <p>${r.reasonText}</p>
      <div class="segment-callout">
        <span class="segment-callout-label">${icon('clock')}다시 확인할 부분</span>
        <strong>${formatTime(firstSegment.start)} ~ ${formatTime(firstSegment.end)}</strong>
      </div>
      <button class="secondary-action" data-toast="의심 구간 재생 UI입니다.">${icon('play')}${state.easy ? '이 부분 다시 보기' : (isMedium ? '다시 확인할 구간 보기' : '의심 구간 다시 보기')}</button>
    </article>`;
  }

  let actionCard = '';
  if (isHigh) {
    actionCard = `<article class="card result-card">
      <div class="card-heading"><span class="heading-icon">${icon('shield')}</span><h2>지금 이렇게 해보세요</h2></div>
      <ol class="advice-list">
        <li><span class="advice-number">1</span><span>중요한 결정은 잠시 멈추세요.</span></li>
        <li><span class="advice-number">2</span><span>원래 알고 있던 번호로 직접 전화해보세요.</span></li>
        <li><span class="advice-number">3</span><span>가족이나 다른 사람에게 한 번 더 확인하세요.</span></li>
      </ol>
      <div class="button-stack">
        <button class="primary-action" data-toast="가족에게 공유할 수 있는 버튼 자리입니다.">${icon('users')}가족에게 확인하기</button>
        <button class="outline-action" data-toast="공식 출처 확인 기능 자리입니다.">${icon('link')}다른 출처 확인하기</button>
      </div>
    </article>`;
  } else if (isMedium) {
    actionCard = `<article class="card result-card">
      <div class="card-heading"><span class="heading-icon">${icon('shield')}</span><h2>지금 이렇게 해보세요</h2></div>
      <ol class="advice-list">
        <li><span class="advice-number">1</span><span>이 영상만으로 결론 내리지 마세요.</span></li>
        <li><span class="advice-number">2</span><span>표시된 의심 구간을 다시 확인하세요.</span></li>
        <li><span class="advice-number">3</span><span>공식 계정이나 다른 출처와 비교하세요.</span></li>
      </ol>
      <button class="outline-action" data-toast="다른 출처 비교 기능 자리입니다.">${icon('link')}다른 출처와 비교하기</button>
    </article>`;
  } else {
    actionCard = `<article class="card result-card">
      <div class="card-heading"><span class="heading-icon">${icon('shield')}</span><h2>지금 이렇게 해보세요</h2></div>
      <p>큰 조작 신호는 없지만, 중요한 내용이라면 원본 출처도 함께 확인해주세요. AI 분석 결과가 영상의 진위를 100% 보장하지는 않습니다.</p>
      <button class="outline-action" style="margin-top:16px" data-toast="원본 출처 확인 기능 자리입니다.">${icon('link')}원본 출처 확인하기</button>
    </article>`;
  }

  return `<section class="screen result-${type}">
    <div class="back-row"><button class="back-button" data-route="home" aria-label="홈으로 돌아가기">${icon('back')}</button></div>
    <div class="result-hero">
      <p class="analysis-kicker">분석 결과</p>
      <h1>${r.title}</h1>
      <p class="signal-title">행동 신호등</p>
      <div class="traffic-status" role="img" aria-label="현재 행동 신호 상태: ${levelLabel(r.riskLevel)}">
        ${['LOW','MEDIUM','HIGH'].map(level => `<div class="traffic-step ${level === r.riskLevel ? 'is-current' : ''} ${levelClass(level)}">
          <span class="traffic-node">${icon(level === 'LOW' ? 'check' : 'alert')}</span>
          <span class="traffic-label">${signalLabel(level)}</span>
          <span class="traffic-current">${level === r.riskLevel ? '현재' : '&nbsp;'}</span>
        </div>`).join('')}
      </div>
      <p class="result-description">${description}</p>
      ${state.easy ? `<button class="secondary-action voice-button" data-speak>${icon('volume')}결과 읽어드리기</button>` : ''}
    </div>
    ${reasonCard}
    ${actionCard}
    <div class="detail-link-wrap technical-only"><button class="text-action" data-route="details">${icon('eye')}자세한 AI 분석 보기</button></div>
    <div class="dev-switcher card technical-only ${state.showDevTools ? 'is-visible' : ''}">
      <p>개발용 결과 미리보기</p>
      <div class="dev-buttons">${['LOW','MEDIUM','HIGH'].map(level => `<button class="${level === r.riskLevel ? 'active' : ''}" data-risk="${level}">${level}</button>`).join('')}</div>
    </div>
  </section>`;
}

function detailsView() {
  const r = state.result;
  const chart = [32, 46, 39, 84, 91, 58, 42, 76, 35];
  return `<section class="screen">
    <div class="back-row"><button class="back-button" data-route="result" aria-label="결과로 돌아가기">${icon('back')}</button></div>
    <div class="page-heading"><h1 class="page-title">상세 AI 분석</h1><p>전문 사용자를 위한 세부 분석 정보입니다.</p></div>
    <article class="card score-card">
      <p class="score-label">전체 조작 의심 점수</p>
      <p class="score-value ${levelClass(r.riskLevel)}">${pct(r.overallScore)}</p>
      <p class="score-caption">모델이 감지한 의심 신호의 상대적 강도</p>
    </article>
    <article class="card detail-card">
      <h2>구간별 의심 점수</h2>
      <div class="bar-chart" aria-label="시간 구간별 의심 점수 막대 그래프">${chart.map((v,i) => `<div class="chart-column ${v > 70 ? 'suspicious' : ''}"><div class="chart-bar" style="height:${v}%"></div><span>${i * 3}초</span></div>`).join('')}</div>
    </article>
    <article class="card detail-card">
      <h2>영역별 분석</h2>
      <div class="score-rows">
        ${scoreRow('입 영역 (Mouth)', r.mouthScore)}
        ${scoreRow('눈 영역 (Eye)', r.eyeScore)}
        ${scoreRow('시간 흐름 (Temporal)', r.temporalScore)}
        ${scoreRow('모델 Confidence', .93)}
      </div>
    </article>
    <article class="card detail-card">
      <h2>의심 구간</h2>
      <div class="segment-list">${r.suspiciousSegments.length ? r.suspiciousSegments.map((s,i) => `<div class="segment-item"><span class="segment-time">${formatTime(s.start)}–${formatTime(s.end)}</span><span class="segment-desc">${i ? '얼굴 경계 변화 감지' : '입 모양과 음성 흐름 불일치'}</span>${icon('play')}</div>`).join('') : '<p class="record-meta">표시할 의심 구간이 없습니다.</p>'}</div>
    </article>
    <article class="card detail-card">
      <h2>의심 프레임</h2>
      <div class="frame-grid"><div class="frame" data-time="00:08"></div><div class="frame" data-time="00:10"></div><div class="frame" data-time="00:22"></div></div>
    </article>
    <article class="card detail-card">
      <h2>Grad-CAM Heatmap</h2>
      <div class="heatmap" role="img" aria-label="입 주변에 집중된 히트맵 예시"></div>
    </article>
    <p class="disclaimer">이 수치는 시연용 더미 데이터입니다. 실제 진위 판정이 아니며, AI 점수만으로 중요한 결정을 내려서는 안 됩니다.</p>
  </section>`;
}

function scoreRow(label, value) {
  return `<div><div class="score-row-head"><span>${label}</span><strong>${pct(value)}</strong></div><div class="mini-track"><span style="width:${pct(value)}"></span></div></div>`;
}

function historyView() {
  const filtered = state.filter === 'ALL' ? records : records.filter(r => r.level === state.filter);
  return `<section class="screen">
    <div class="page-heading"><h1 class="page-title">분석 기록</h1><p>이전에 확인한 사진과 영상을 다시 볼 수 있어요.</p></div>
    <div class="filter-row" aria-label="결과 필터">
      ${[['ALL','전체'],['HIGH','다시 확인 필요'],['MEDIUM','추가 확인 필요'],['LOW','큰 조작 신호 없음']].map(([key,label]) => `<button class="filter-chip ${state.filter === key ? 'active' : ''}" data-filter="${key}">${label}</button>`).join('')}
    </div>
    <div class="history-list">${filtered.map(recordCard).join('')}</div>
  </section>`;
}

function helpView() {
  return `<section class="screen">
    <div class="page-heading"><h1 class="page-title">도움말</h1><p>VeriVid를 안전하게 사용하는 방법을 알려드려요.</p></div>
    <div class="faq-list">
      ${faq('이 앱은 무엇을 하나요?', '<p>사진이나 영상 속 얼굴에서 조작이 의심되는 신호를 찾아 알기 쉽게 설명합니다.</p>', true)}
      ${faq('결과 색상은 무슨 뜻인가요?', `<div class="legend"><div class="legend-item"><span class="legend-symbol high">!</span><div><strong>빨강 · 다시 확인 필요</strong><span>조작이 의심되는 신호가 있어요. 중요한 행동 전에 다시 확인해주세요.</span></div></div><div class="legend-item"><span class="legend-symbol medium">?</span><div><strong>노랑 · 추가 확인 필요</strong><span>AI가 판단하기 어려워 한 번 더 확인이 필요해요.</span></div></div><div class="legend-item"><span class="legend-symbol low">✓</span><div><strong>초록 · 큰 조작 신호 없음</strong><span>현재 분석에서 큰 조작 신호를 찾지 못했어요.</span></div></div></div>`)}
      ${faq('AI 결과를 100% 믿어도 되나요?', '<p>아니요. AI도 틀릴 수 있어요. 중요한 내용은 원본 출처와 당사자에게 반드시 다시 확인해주세요.</p>')}
      ${faq('딥페이크가 의심될 때 어떻게 하나요?', '<p>돈을 보내거나 개인정보를 알려주지 마세요. 전화를 끊고, 원래 알고 있던 번호로 직접 연락해 확인하세요.</p>')}
    </div>
    <article class="card emergency-card"><h2>급하게 돈을 요구하나요?</h2><p>먼저 전화를 끊고 가족이나 경찰에 확인하세요. 영상이나 목소리만 믿고 송금하지 마세요.</p></article>
  </section>`;
}

function faq(question, answer, open = false) {
  return `<article class="faq card ${open ? 'open' : ''}"><button aria-expanded="${open}"><span>${question}</span><span class="chevron">${icon('chevron')}</span></button><div class="faq-answer">${answer}</div></article>`;
}

function render() {
  clearInterval(state.analysisTimer);
  const views = { home: homeView, captureGuide: captureGuideView, captureReview: captureReviewView, analyzing: analyzingView, result: resultView, details: detailsView, history: historyView, help: helpView };
  app.innerHTML = (views[state.route] || homeView)();
  easyModeInput.checked = state.easy;
  document.body.classList.toggle('easy-mode', state.easy);
  updateNav();
  hydrateIcons();
  window.scrollTo({ top: 0, behavior: 'auto' });
  if (state.route === 'analyzing') startProgress();
}

function hydrateIcons() {
  document.querySelectorAll('[data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon); });
}

function updateNav() {
  const activeRoute = ['history','help'].includes(state.route) ? state.route : 'home';
  document.querySelectorAll('.nav-item').forEach(item => {
    const active = item.dataset.route === activeRoute;
    item.classList.toggle('is-active', active);
    active ? item.setAttribute('aria-current', 'page') : item.removeAttribute('aria-current');
  });
}

function navigate(route) { state.route = route; render(); app.focus({ preventScroll: true }); }

function startProgress() {
  let progress = 0;
  const statuses = ['얼굴 영역 확인 중', '의심 구간 분석 중', '결과 정리 중'];
  state.analysisTimer = setInterval(() => {
    progress = Math.min(100, progress + Math.ceil(Math.random() * 7));
    const step = progress < 38 ? 0 : progress < 78 ? 1 : 2;
    const bar = document.querySelector('#progressBar');
    if (!bar) return clearInterval(state.analysisTimer);
    bar.style.width = `${progress}%`;
    bar.parentElement.setAttribute('aria-valuenow', String(progress));
    document.querySelector('#progressText').textContent = `${progress}%`;
    document.querySelector('#progressStatus').textContent = statuses[step];
    document.querySelectorAll('.status-step').forEach((el, i) => {
      el.classList.toggle('current', i === step);
      el.classList.toggle('done', i < step);
      if (i < step) el.querySelector('.step-dot').innerHTML = '✓';
    });
    if (progress === 100) { clearInterval(state.analysisTimer); setTimeout(() => navigate('result'), 450); }
  }, 240);
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 2300);
}

function speakResult() {
  const message = `${state.result.title}. ${state.result.easyDescription} ${state.result.riskLevel === 'HIGH' ? '돈을 보내기 전에 가족에게 다시 확인하세요.' : '중요한 내용은 다른 방법으로도 확인해주세요.'}`;
  if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(message);
    utterance.lang = 'ko-KR'; utterance.rate = .88;
    window.speechSynthesis.speak(utterance);
    showToast('결과를 읽어드리고 있어요.');
  } else showToast('이 기기에서는 음성 읽기를 지원하지 않아요.');
}

document.addEventListener('click', event => {
  const routeButton = event.target.closest('[data-route]');
  if (routeButton) return navigate(routeButton.dataset.route);
  if (event.target.closest('[data-start-screen-analysis]')) return navigate('captureGuide');
  if (event.target.closest('[data-prepare-capture]')) {
    if (window.VeriVidAndroid?.prepareScreenCapture) window.VeriVidAndroid.prepareScreenCapture();
    else showToast('화면 녹화 권한은 Android 앱에서 요청됩니다.');
    return;
  }
  if (event.target.closest('[data-play-capture]')) {
    if (state.capturedPath && window.VeriVidAndroid?.playRecordedVideo) window.VeriVidAndroid.playRecordedVideo(state.capturedPath);
    else showToast('녹화 영상 재생 기능 자리입니다.');
    return;
  }
  if (event.target.closest('[data-analyze-capture]')) {
    // TODO: 백엔드 팀의 딥페이크 분석 API 연결 예정
    state.result = mockResults.HIGH;
    return navigate('analyzing');
  }
  if (event.target.closest('[data-recapture]')) return navigate('captureGuide');
  if (event.target.closest('[data-analyze]')) return navigate('analyzing');
  const resultButton = event.target.closest('[data-open-result]');
  if (resultButton) { state.result = mockResults[resultButton.dataset.openResult]; return navigate('result'); }
  const riskButton = event.target.closest('[data-risk]');
  if (riskButton) { state.result = mockResults[riskButton.dataset.risk]; return render(); }
  const filterButton = event.target.closest('[data-filter]');
  if (filterButton) { state.filter = filterButton.dataset.filter; return render(); }
  const toastButton = event.target.closest('[data-toast]');
  if (toastButton) return showToast(toastButton.dataset.toast);
  if (event.target.closest('[data-speak]')) return speakResult();
  const faqButton = event.target.closest('.faq > button');
  if (faqButton) {
    const item = faqButton.parentElement;
    item.classList.toggle('open');
    faqButton.setAttribute('aria-expanded', String(item.classList.contains('open')));
  }
});

function formatCapturedDuration(durationMs) {
  const totalSeconds = Math.max(0, Math.round(durationMs / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

window.showNativeMessage = message => showToast(message);
window.onNativeRecordingComplete = (path, durationMs) => {
  state.capturedPath = path;
  state.capturedName = path.split(/[\\/]/).pop() || 'video_capture_001.mp4';
  state.capturedDurationMs = Number(durationMs) || 0;
  navigate('captureReview');
};

easyModeInput.addEventListener('change', () => {
  state.easy = easyModeInput.checked;
  if (state.easy && state.route === 'details') state.route = 'result';
  render();
  showToast(state.easy ? '쉬운 설명 모드를 켰어요.' : '기본 설명 모드로 돌아왔어요.');
});

render();
