# 검증 기록

검증일: 2026-09-30. Windows / Python 3.11.9. 모델 저장소의 추적 파일은 수정하지 않았습니다.

## 자동 테스트

`python -m pytest -q`와 `ruff check app tests scripts`, `python -m compileall -q app scripts`를 실행합니다. 자동 테스트는 임시 DB/저장소 및 제어된 모델 adapter를 사용합니다.

최종 결과: **39개 테스트 통과**, Ruff 통과, Python 구문 검사 통과. 생성된 OpenAPI에는 세션 bearer 인증 및 수용량 초과 503 응답을 포함합니다.

주요 검증: 세션 인증, 소유자 격리, 업로드 제한(길이 미지정 chunked 포함), 동시 업로드 quota, 전역 작업/세션 cap, worker 동시 claim, 만료 작업 배제, 중단 작업 처리, 실패 영상 삭제, orphan 정리, 잘못된 모델 output 차단, 신호등 경계값, XAI 부분 실패, 정답 사전 비공개, 답변 순서/중복 제출, 게임 전체 채점, OpenAPI 인증 스키마.

## 실제 가중치 검증

```powershell
.venv-model\Scripts\python scripts/smoke_model.py ..\exports\deeptector-screens\05-xai-detail.png
.venv-model\Scripts\python scripts/smoke_api.py ..\exports\deeptector-screens\05-xai-detail.png
```

기존 시안 이미지를 16프레임 MP4로 임시 인코딩했습니다. 입력 이미지는 변경하지 않았고 만들어진 시험 영상/DB/결과 파일은 임시 디렉토리 종료 시 제거했습니다.

결과:

- CPU에서 ConvNeXt 5개와 MC3-18 6개 가중치 로드 및 추론 성공.
- MTCNN 얼굴 crop 16개 추출.
- spatial `0.013446978782303632`, temporal `0.4708164072206988`, fused `0.2421316930015012`.
- 모델 식별자 `dual-branch-d28bda40d902b7d0`, calibration `d94834235df539b1`.
- Grad-CAM 2장 및 원본 얼굴/입 크롭 2장 PNG 생성, warnings 비어 있음.
- FastAPI 업로드 202 → SQLite QUEUED → 실제 worker COMPLETED → 인증된 PNG 조회 → 분석 삭제 204 → 조회 404 확인.
- 인증 없는 artifact 요청 401, 추론 후 원본 파일 삭제 확인.

이 검증은 모델 통합 및 파일·API 계약 확인이며 탐지 정확도/일반화/처리량 평가가 아닙니다. 정지 이미지를 반복한 영상의 판정은 실제 영상 성능의 근거로 쓰면 안 됩니다.

## 남은 배포·데이터 조건

- 사용자 영상과 정답이 있는 평가셋에서 운영 성능/노랑 임계값을 검증해야 합니다.
- 게임용 실제 영상·출처·사용권·정답 manifest는 팀이 공급해야 합니다. API/채점은 fixture 테스트를 통과했으며 실제 게임용 데이터가 없는 상태에서는 생성 API가 명시적으로 409를 반환합니다.
- PostgreSQL/다중 서버/CUDA 배포는 이번 실행 검증 대상이 아닙니다. 구현 런타임은 SQLite/로컬 파일입니다.
- 기존 prototype HTML은 정적 시연 화면이며 백엔드가 자동 연결되지는 않습니다. frontend 연동 예제를 제공합니다.
- 검증 환경의 Starlette/httpx 및 HTTP 상태 상수에 deprecation warning이 있습니다. 테스트 실패는 아닙니다.

전체 재현 환경은 `requirements-lock.txt`, `requirements-model-lock.txt`에 기록했습니다. 각 환경에서 `pip check`도 통과했습니다.
