# Backend API v1

Base URL `http://127.0.0.1:8000`. 상세 요청 스키마는 `/docs` 및 `/openapi.json`에서 확인합니다. 오류 body는 `{ "error": { "code": "...", "message": "...", "details": {} } }`입니다.

## 인증

`POST /api/v1/sessions` → 201 `{session_id,token,expires_at}`. 이후 모든 분석/게임 요청에 `Authorization: Bearer <token>`을 보냅니다. 토큰은 발급 시 한 번만 반환하고 DB에는 SHA-256 digest만 저장합니다. 토큰 소지자가 해당 세션의 소유자이며 계정 로그인/비밀번호/토큰 복구 기능은 없습니다.

## Endpoint 목록

| Method / Path | 동작 | 정상 상태 |
|---|---|---|
| GET `/health/live` | API 프로세스 생존 | 200 |
| GET `/health/ready` | DB/저장소 준비 | 200/503 |
| GET `/api/v1/model-info` | 구성 모드·모델 경로 존재 여부 | 200 |
| POST `/api/v1/sessions` | 익명 세션 생성 | 201 |
| POST `/api/v1/analyses` | multipart `video` 업로드·분석 예약 | 202 |
| GET `/api/v1/analyses?limit=50&offset=0` | 내 분석 이력 (limit 1~100) | 200 |
| GET `/api/v1/analyses/{id}` | 상태·결과·XAI URL | 200 |
| DELETE `/api/v1/analyses/{id}` | 완료/실패 분석과 파일 삭제 | 204 |
| GET `/api/v1/analyses/{id}/artifacts/{kind}` | 인증된 이미지 다운로드 | 200 |
| POST `/api/v1/games` | 게임 생성, JSON `{rounds:10}` | 201 |
| GET `/api/v1/games/{id}` | 게임 진행 상태 | 200 |
| GET `/api/v1/games/{id}/rounds/{n}` | 문제/영상 URL, 제출 후 결과 | 200 |
| GET `/api/v1/games/{id}/rounds/{n}/video` | 해당 문제 MP4 | 200/206 |
| POST `/api/v1/games/{id}/rounds/{n}/answer` | 답변·확신도 제출, 즉시 비교 결과 | 200 |
| GET `/api/v1/games/{id}/result` | 전체 완료 후 점수·문제 분류 | 200 |

모델은 worker subprocess에서 로드하므로 model-info의 `loaded=false`는 API에 모델이 로드되지 않았다는 뜻입니다. API readiness는 GPU/worker의 실행 가능성을 보장하지 않습니다.

## 분석 흐름

1. 세션 토큰 발급.
2. FormData `video` 필드 업로드. `Content-Type`은 브라우저가 boundary를 생성하게 둡니다.
3. 응답 `id`로 1~2초 간격 poll. `status`와 `stage`를 화면에 표시.
4. COMPLETED이면 `result.policy.traffic_light`, `result.policy.verdict`, 점수와 artifact 표시.
5. FAILED이면 `error.code/message/details` 표시. 얼굴 부족은 details의 detected/required 사용.

대기 상태 예시:

```json
{
  "id": "uuid",
  "filename": "sample.mp4",
  "status": "QUEUED",
  "stage": "QUEUED",
  "created_at": "2026-09-30T00:00:00.000Z",
  "updated_at": "2026-09-30T00:00:00.000Z",
  "expires_at": "2026-10-01T00:00:00.000Z",
  "result": null,
  "error": null,
  "artifacts": []
}
```

완료 응답의 `result` 핵심 부분 (예시값):

```json
{
  "spatial": 0.918,
  "temporal": 0.528,
  "fused": 0.723,
  "n_faces": 16,
  "family": "공간 분석 우세",
  "faithfulness": "모델 주목 영역이며 조작의 물증이 아님",
  "model_version": "dual-branch-<hash>",
  "calibration_version": "<hash>",
  "is_demo": false,
  "warnings": [],
  "metadata": {"fps":30,"frame_count":1020,"width":1920,"height":1080,"duration_seconds":34},
  "policy": {
    "verdict": "FAKE",
    "traffic_light": "RED",
    "reason": "FUSED_THRESHOLD",
    "primary_signal": "SPATIAL",
    "signal_score": 72.3,
    "policy_version": "traffic-v1-experimental",
    "thresholds": {"fused_fake":0.5,"branch_fake":0.85,"branch_caution":0.5},
    "score_interpretation": "Model signal, not a verified probability of forgery"
  }
}
```

최상위 `artifacts`는 `{kind,mime_type,url}` 배열입니다. `cam_spatial`, `cam_temporal`, `spatial_original`, `temporal_original`을 지원합니다. XAI 일부 실패 시 해당 artifact가 없고 warnings가 채워집니다. 시연 모드에서는 실제 히트맵을 꾸며내지 않고 artifact가 비어 있습니다.

`<img src>`나 `<video src>`에는 bearer header를 직접 넣을 수 없습니다. `examples/client.js`처럼 인증된 fetch → Blob URL을 사용하고 사용 후 revoke하세요. 원본 업로드 영상은 분석 후 삭제하므로 재생 API를 제공하지 않습니다.

진행률은 실제 세부 모델 진행률을 알 수 없어 숫자 65%를 임의로 반환하지 않습니다. 화면의 prototype 진행률을 stage 기반 상태 표시로 연결하세요.

## 게임

실제 라벨이 확인된 데이터셋은 저장소에 포함되어 있지 않습니다. 운영자가 MP4 및 정답 출처/사용권/AI 점수를 준비해 예시 manifest를 수정한 뒤 등록합니다.

```powershell
.venv\Scripts\python -m app.game examples/my-game-manifest.json
```

정답은 운영자가 확인한 ground truth입니다. 모델의 판정 자체를 정답으로 사용하지 않습니다. 데이터가 부족하면 게임 생성은 409 `GAME_DATASET_NOT_READY`입니다. 공개 API로 정답을 등록하거나 수정할 수 없습니다.

답변 body: `{"verdict":"FAKE","confidence":"HIGH"}`. verdict는 REAL/FAKE, confidence는 LOW/MEDIUM/HIGH. 답변 전에는 ground truth와 AI 점수를 응답하지 않습니다. 순서대로 제출해야 하며 동일 답변 재전송은 동일 결과를 반환하고 변경 시 409입니다.

전체 결과는 `user_score`, `ai_score`, 0~1 `user_accuracy`, `ai_accuracy`, 문제 번호 배열을 담은 `categories`(human_only, ai_only, both_correct, both_wrong), 라운드별 결과를 반환합니다. 시안의 게임 예시에는 합계가 맞지 않는 수치가 있어 그대로 저장하지 않고 답변으로 계산합니다.

## 오류와 UI 처리

| 상태/코드 | 처리 |
|---|---|
| 401 AUTH_REQUIRED/INVALID_SESSION | 세션 필요 또는 만료 |
| 400 EMPTY_UPLOAD/UNSUPPORTED_VIDEO_TYPE | 파일 선택 안내 |
| 413 UPLOAD_TOO_LARGE | 크기 제한 안내 |
| 422 VALIDATION_ERROR | 요청 field 수정 |
| 429 QUEUE_LIMIT_REACHED/GAME_LIMIT | 진행 중 작업 완료 후 재요청 |
| 503 GLOBAL_QUEUE_LIMIT/SESSION_CAPACITY | 전체 수용량 초과; 나중에 재시도 또는 만료 정리 |
| 503 UPLOAD_CAPACITY | 동시에 수신 중인 업로드 한도 초과; 나중에 재시도 |
| 404 ANALYSIS_NOT_FOUND/GAME_NOT_FOUND | 타인 리소스도 같은 404 |
| 409 ANALYSIS_NOT_TERMINAL | 처리 중 삭제 불가 |
| 작업 FAILED / INVALID_VIDEO | 확장자는 허용돼도 실제 decode 실패 |
| 작업 FAILED / INSUFFICIENT_FACE_DATA | 회색 오류 상태; GREEN으로 표시 금지 |
| 작업 FAILED / VIDEO_LIMIT_EXCEEDED | 5분/프레임/해상도 제한 |
| 작업 FAILED / MODEL_DEPENDENCIES_MISSING | worker 환경 설치 필요 |
| 작업 FAILED / MODEL_UNAVAILABLE | 가중치/보정 파일 확인 |
| 작업 FAILED / ANALYSIS_TIMEOUT, WORKER_TIMEOUT | 재업로드 또는 worker 점검 |
| 작업 FAILED / INFERENCE_FAILED, ANALYSIS_FAILED | worker 로그 확인 |

비동기 분석 실패는 최초 업로드의 HTTP 상태를 바꾸지 않습니다. poll 자체는 200이며 body status=FAILED입니다.

## 참고한 공식 문서

- https://fastapi.tiangolo.com/tutorial/request-files/ — multipart UploadFile
- https://fastapi.tiangolo.com/tutorial/background-tasks/ — 무거운 계산 작업 분리
- https://www.sqlite.org/lang_transaction.html — SQLite 쓰기 트랜잭션
