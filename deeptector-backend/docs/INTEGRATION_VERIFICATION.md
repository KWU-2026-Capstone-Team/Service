# 통합판 검증 기록 — 2026-11-04

대상: `integration/deepgame-service` 로컬 작업 트리, Windows/Python 3.11.
원본 deepGame: `85d18621a8d82e7299036aa8725b150932b1a617`.
범위: 로컬 demo 분석 + 데이터셋 라벨 기반 게임. 실제 탐지 정확도 평가가 아니다.

## 실행한 검증

| 검증 | 결과 |
|---|---|
| backend `python -m pytest -q` | 61 passed, 의존성 deprecation 경고 5개 |
| backend `ruff check app scripts tests` | 통과 |
| frontend `npm run lint` | 오류 0, 인증 Blob 이미지의 일반 img 사용 경고 1 |
| frontend `npm run build` | 정적 export 및 TypeScript 검사 통과 |
| `npm audit --omit=dev` | 취약점 0; 전체 개발 의존성 audit에는 moderate 16건 별도 보고됨 |
| `git diff --check` | 통과 |
| `scripts/smoke_integrated.py` | 수정 후 재실행 통과 |
| `/health/worker` | 시작 중 503 → 준비 후 200; mode demo, model_loaded false |

HTTP smoke는 5개 화면의 정적 HTML, Windows RSC segment 파일, private 경로 404, 세션 소유권, 15라운드 균형 게임, 206 Range 영상, 순차 채점/중복 제출, skip, AI null, 이력/삭제를 확인한다. 합성 MP4 업로드 → worker → COMPLETED/demo 신호등 → 기록/파일 삭제도 확인한다. HTTP smoke가 생성한 분석/게임 기록은 삭제했다.

회귀 테스트는 기존 스키마 보존·롤백·동시 마이그레이션, 비대칭 부분 스키마 거절 및 원본 보존, 불완전 AI 평가, 과거 비정상 시간 데이터의 API 직렬화, worker 부재 업로드503, worker 배타 잠금과 중단 분석 복구를 포함한다.

## 브라우저 직접 검증

Codex 내장 브라우저에서 같은 출처 `http://127.0.0.1:5173/`로 다음을 확인했다.

- 홈 → 5라운드 시작 → 실제 로컬 게임 MP4 재생.
- 답변 → 정답/해설 표시 → 새로고침 후 다음 미응답 라운드 복구.
- 한 라운드 skip → 정답 100점 → 연속 정답 110점.
- 최종 결과 210점, 답한 수 4, skip 1, 정확도 50%, AI 평가 없음.
- 합성 `ui-smoke.mp4`를 파일 선택기로 업로드 → QUEUED → COMPLETED.
- DEMO 표기, RED/72.3 고정 신호, XAI 없음 표시.
- 동일 탭 이력에서 게임 및 분석을 함께 조회.

로컬 증거: `.artifacts/integrated-game-result.png`, `.artifacts/integrated-history.png`.
브라우저 검증용 게임 1건과 합성 분석 1건은 결과 확인용으로 남겼다. 개인 영상은 사용하지 않았다. 이 증거 파일은 Git에 포함하지 않는다.

Windows 빌드에서 RSC 파일 경로가 중첩 디렉토리로 생성되어 prefetch 404가 발생하는 것을 확인했다. `frontend/scripts/normalize-export.mjs`로 필요한 평탄 경로 별칭을 생성하고 HTTP 200을 검증했다. 관련 upstream: https://github.com/vercel/next.js/issues/85374 .

## 독립 검토 및 제한

code-reviewer와 architect가 별도로 최초 검토했다. 발견된 부분 스키마 수락, 한쪽 AI 평가, 과도한 시간 값, 무제한 이력 쿼리, worker 준비 상태, 중단 분석 처리, 분석 요청 race 문제를 수정하고 위 회귀 검증을 추가했다.

code-reviewer 최종 재검토는 CLEAN(남은 코드 지적 0건)이며 61개 테스트를 독립 재실행했다. 최종 architect 재검토는 도구 사용량 한도로 완료되지 않았다. 따라서 **독립 최종 검토 미완료이며 merge-ready 승인으로 표시하지 않는다.** 기존 main/Pages/PDF는 보존한다. 사용자 요청에 따라 통합 코드는 integration/deepgame-service 브랜치로 공유하며, main 병합과 공개 서비스 배포는 별도 작업이다.

- 실제 모델 24개 게임 영상 평가는 실행하지 않았다. AI 평가 CLI와 실제 모델 adapter 연결은 제공되지만 이번 증거는 demo 흐름이다.
- worker 준비 상태는 의존성/필수 파일과 heartbeat 검사이다. 가중치 로딩 및 추론 성공은 실제 작업 결과로 판단한다.
- 영상의 공개 재배포 권한은 미확인이다. 로컬 참조만 등록했으며 영상과 정답 JSON은 정적 export에 없다.
- 계정 로그인/복구, 공개 backend hosting, 다중 서버 운영, 공정한 온라인 랭킹은 구현 범위 밖이다.
- 게임 이력은 최신 50건을 기본 표시한다. API는 limit/offset으로 이전 기록 조회를 지원한다.

실행 방법 및 데이터 경로는 [INTEGRATED_SERVICE.md](INTEGRATED_SERVICE.md)를 따른다.
