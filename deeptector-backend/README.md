# DeepTector Backend

영상 분석 및 AI vs 사람 게임을 위한 독립 백엔드. 기존 모델 저장소 옆에 배치하며 원본 모델 코드는 읽기 전용으로 참조합니다.

## 실행

GitHub Pages와 이 PC를 연결해 시험하려면 [로컬 demo 안내](docs/LOCAL_DEMO.md)를 먼저 보세요. `scripts/run_local_demo.py`는 Pages CORS 설정과 별도 테스트 저장소를 사용해 API/worker를 한 번에 실행합니다.

Python 3.11을 기준으로 검증합니다. API와 worker는 **같은 DB/저장소/모드 환경 변수**를 사용해야 합니다.

재현용 전체 의존성은 `requirements-lock.txt`(API/테스트)와 `requirements-model-lock.txt`(모델)에 고정했습니다. 아래 설치 명령의 requirements 파일을 해당 lock 파일로 대체하면 검증한 전체 패키지 버전을 재현합니다.

```powershell
cd deeptector-backend  # 이 저장소 루트에서 실행
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
$env:DEEPTECTOR_INFERENCE_MODE='demo'
.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

다른 터미널에서:

```powershell
cd deeptector-backend  # 이 저장소 루트에서 실행
$env:DEEPTECTOR_INFERENCE_MODE='demo'
.venv\Scripts\python -m app.worker
```

Swagger: http://127.0.0.1:8000/docs · OpenAPI: http://127.0.0.1:8000/openapi.json

`demo`는 영상 디코딩 후 **고정 시연 점수**를 반환하며 `is_demo=true`, 경고 코드가 붙습니다. 얼굴 검출이나 실제 분류를 수행하지 않습니다. 기본 설정은 `real`이며 실제 모드 실패 시 demo로 대체하지 않습니다.

## 실제 모델 실행

모델용 환경은 facenet-pytorch와 NumPy 호환성을 위해 별도로 고정합니다. `requirements.txt`와 `requirements-model.txt`를 한 환경에 섞지 마세요.

```powershell
python -m venv .venv-model
.venv-model\Scripts\python -m pip install -r requirements-model.txt
$env:DEEPTECTOR_INFERENCE_MODE='real'
.venv-model\Scripts\python -m app.worker
```

API는 가벼운 `.venv`에서 `real` 환경 변수로 실행할 수 있습니다. worker가 자신의 Python으로 모델 subprocess를 실행합니다. API 프로세스는 torch를 import하지 않습니다. 작업마다 subprocess가 모델을 로드하므로 시작 비용이 있으나 시간 제한 시 강제 종료할 수 있고 XAI 전역 hook이 작업 사이에 공유되지 않습니다. 기본은 단일 worker입니다. CPU 가능, CUDA 사용은 해당 환경에 맞는 PyTorch 설치가 필요합니다.

기본 모델 위치: `../AI-Generated-Media-Detection/deepfake_detector`. `DEEPTECTOR_MODEL_DIR`로 변경할 수 있습니다. 공간 5개, 시간 6개 가중치와 `calibration.json`이 모두 필요합니다.

이 서비스 저장소에는 외부 모델 폴더가 포함되어 있지 않습니다. 실제 모드 실행 전 `DEEPTECTOR_MODEL_DIR`에 외부 모델의 절대 경로를 설정하세요. `anseonghwan/deeptector` 저장소 `main`의 연구 모델은 이 adapter의 대상 모델과 다릅니다.

## 제공 기능

- 익명 세션 발급 및 bearer token 기반 소유자 격리
- 영상 업로드 → DB 대기열 → worker 추론 → 결과 조회
- 점수/판정/신호등/모델 버전/보정 버전/프레임 메타데이터 저장
- 원본 얼굴·입 크롭 및 공간·시간 Grad-CAM 조회
- 실패 사유, XAI 부분 실패, 만료 정리와 분석 삭제
- 검증된 게임 영상 등록 CLI, 1~10문제 게임, 답변 잠금 및 서버 채점
- SQLite 스키마와 제약조건, 테스트, OpenAPI 및 팀 공유 문서

DB는 지금 바로 실행할 수 있는 SQLite를 구현했습니다. PostgreSQL은 운영 전환 설계 대상으로 [DB 문서](docs/DATABASE.md)에 차이와 변경 지점을 명시했습니다. 환경 변수만 바꾸는 PostgreSQL 런타임 지원은 포함하지 않습니다.

## 설정

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| `DEEPTECTOR_DATA_DIR` | 서비스의 `data/` | 로컬 데이터 루트 |
| `DEEPTECTOR_DB_PATH` | `data/deeptector.sqlite3` | SQLite 파일 |
| `DEEPTECTOR_STORAGE_DIR` | `data/storage` | 임시 영상/결과 이미지 |
| `DEEPTECTOR_MODEL_DIR` | 위 모델 위치 | 신뢰된 로컬 코드·가중치 |
| `DEEPTECTOR_INFERENCE_MODE` | `real` | `real` 또는 `demo` |
| `DEEPTECTOR_MAX_UPLOAD_BYTES` | 524288000 | 파일 최대 500MiB |
| `DEEPTECTOR_RETENTION_HOURS` | 24 | 분석 결과 보관 기간 |
| `DEEPTECTOR_JOB_TIMEOUT_SECONDS` | 3600 | 모델 subprocess 제한 시간 |
| `DEEPTECTOR_MAX_QUEUED_PER_SESSION` | 3 | 동시 대기/처리 작업 수 |
| `DEEPTECTOR_MAX_ACTIVE_GLOBAL` | 32 | 모든 세션의 대기/처리 작업 합계 제한 |
| `DEEPTECTOR_MAX_SESSIONS` | 10000 | 만료되지 않은 세션 수 제한 |
| `DEEPTECTOR_MAX_CONCURRENT_UPLOADS` | 2 | API 프로세스당 수신 중인 업로드 수 |
| `DEEPTECTOR_SESSION_TTL_HOURS` | 720 | 세션 수명 |
| `DEEPTECTOR_CORS_ORIGINS` | localhost/127.0.0.1의 3000,5173 | 쉼표 구분 frontend origin |

`.env`는 자동 로드하지 않습니다. PowerShell `$env:` 또는 배포 환경에서 설정하세요. 원본 영상은 분석 종료 시 삭제하고, 결과 이미지/메타데이터는 만료 정리 시 삭제합니다.

```powershell
.venv\Scripts\python -m app.worker --once
.venv\Scripts\python -m app.worker --cleanup
.venv\Scripts\python -m pytest -q
.venv\Scripts\ruff check app tests scripts
```

상시 worker는 작업 사이에 60초 간격으로 만료 정리를 시도합니다. 긴 추론 중에도 정시 정리가 필요하면 `--cleanup`을 작업 스케줄러에서 별도로 실행하세요. 공용 인터넷 배포 전에는 TLS, 프록시 업로드/요청 제한, 사용자 계정/세션 발급 제한, 저장소 용량 및 GPU 운영 설정이 필요합니다. 현재 인증은 계정 로그인 대신 소유권을 가진 익명 토큰을 사용합니다. API는 단일 프로세스를 기준으로 하며 다중 프로세스 배포 시 업로드 동시성 한도를 프록시에서 공유해야 합니다.

## 문서

- [API 및 frontend 연동](docs/API.md)
- [DB 스키마·관계·보관 정책](docs/DATABASE.md)
- [모델 출력·신호등 정책·제약](docs/MODEL.md)
- [게임 데이터 등록 예시](examples/game-manifest.example.json)
- [fetch API client](examples/client.js)
- [공유 가능한 OpenAPI JSON](docs/openapi.json)
- [검증 기록](docs/VERIFICATION.md)

`../prototype/prototype-version.html`은 시연용 정적 화면으로 유지됩니다. API 연동에는 `examples/client.js`의 업로드/poll/게임 호출을 화면 이벤트에 연결하세요.
