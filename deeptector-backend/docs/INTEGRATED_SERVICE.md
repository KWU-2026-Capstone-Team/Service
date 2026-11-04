# 통합 서비스 실행 및 경계

## 구성

- `frontend/`: deepGame의 게임 UX를 반영한 Next.js 정적 export. 홈·분석·게임·결과·기록.
- `deeptector-backend/app/`: 공통 세션, 업로드/추론, 게임/서버 채점/기록, 영상 스트리밍.
- `frontend/out/`만 정적으로 제공합니다. 저장소 루트, 정답 JSON, DB, 가중치를 웹에 노출하지 않습니다.
- Node는 설치/빌드 단계에 사용하고, 빌드 이후 실행 프로세스는 Python API와 worker입니다.
- 기존 `index.html`, `web/`, `prototype/`와 PDF는 보존합니다. GitHub Pages의 기존 페이지는 자동 교체하지 않습니다.

## 현재 PC 실행

```powershell
cd C:\Users\user\Desktop\deeptest\kwu-service
.\Start-Service.ps1 -Setup
```

Windows Python 3.11과 Node.js가 필요합니다. `-Setup`은 가상환경/고정 패키지 설치와 프론트엔드 빌드를 수행합니다. 다음부터는 `.\Start-Service.ps1`만 사용합니다. 화면 변경 후 `.\Start-Service.ps1 -Build`로 다시 빌드합니다. PowerShell 정책 때문에 스크립트 실행이 막힌다면 시스템 정책을 낮추지 말고 아래 Python 명령을 사용하세요(설치/빌드 완료 후).

```powershell
cd C:\Users\user\Desktop\deeptest\kwu-service\deeptector-backend
.\.venv\Scripts\python.exe scripts\run_integrated.py
```

접속 주소: http://127.0.0.1:5173/ · API 명세: http://127.0.0.1:5173/docs

다른 프로그램이 포트를 사용하면 `-Port 5174` 또는 Python `--port 5174`로 변경하세요. 기존 프로그램을 임의로 종료하지 않습니다. Ctrl+C는 API/worker를 함께 종료합니다.

이전의 `deeptest/deeptector-backend`, `deeptest/deeptector-service/deeptector-backend`와 구별하세요. **통합 코드는 kwu-service 안에 있습니다.**

## 데이터 보관

통합판은 `deeptector-backend/data/integrated/`를 사용합니다. 기존 수동 테스트/Pages demo의 DB와 파일을 이동하거나 삭제하지 않습니다. 같은 탭 새로고침은 토큰을 유지하지만 탭 종료 또는 브라우저 저장소 삭제 시 소유권을 잃을 수 있습니다. 계정 로그인/복구 기능은 아닙니다.

기존 DB의 게임 테이블은 전체 필수 컬럼을 검사하는 트랜잭션 마이그레이션으로 보존·확장합니다. 알 수 없는 부분 변경 상태는 실행을 거부합니다. 실제 운영 DB를 이전할 때는 프로세스를 종료하고 DB를 백업한 뒤 마이그레이션을 검증하세요. SQLite 운영에서 다중 서버 확장은 별도 작업입니다.

통합 실행기는 같은 데이터 디렉토리의 worker를 하나만 허용합니다. `/health/worker`는 2초 주기의 heartbeat와 실행 ID를 검사하며 10초 이상 갱신되지 않으면 준비 안 됨으로 응답합니다. worker가 준비되지 않으면 분석 업로드는 503입니다. `/health/ready`는 API/DB/storage 상태만 확인합니다. 준비 상태는 모델 정확도나 실제 모델 로딩 성공을 보증하지 않습니다.

게임은 서버 재시작 후 같은 세션으로 이어갈 수 있습니다. 중단 당시 `PROCESSING`이던 분석은 다음 통합 worker 시작 시 `FAILED / WORKER_RESTARTED`로 정리하고 업로드 사본을 제거합니다. 해당 영상은 다시 업로드해야 합니다. `QUEUED` 작업은 이어서 처리됩니다. 일반 다중 worker 실행에는 이 로컬 전용 복구가 적용되지 않습니다.

## 게임 데이터

원본: https://github.com/KWU-2026-Capstone-Team/deepGame 의 `src/data/videos.json` 및 `public/videos/` 24개 클립. 현재 PC에는 `C:\Users\user\Desktop\deeptest\deepGame-reference`에 별도 복제했습니다. 코드 변경 없이 읽기 전용으로 참조합니다.

최초 한 번 저장소 루트에서 등록합니다. 기본 경로는 이 저장소 옆의 `deepGame-reference`이며 다른 위치는 `-Source`로 지정하세요. 등록은 동일 파일/라벨에 대해 재실행할 수 있고, 원본 파일을 복사·삭제하지 않습니다.

```powershell
.\Import-Game.ps1
# 또는
.\Import-Game.ps1 -Source 'C:\Projects\deepGame'
```

Python 직접 실행: 백엔드 폴더에서 `.\.venv\Scripts\python.exe -m app.import_deepgame 'C:\Projects\deepGame' --data-dir data\integrated`.

게임 영상은 API가 세션 소유권을 검사한 뒤 스트리밍합니다. 원본 파일은 분석 업로드 사본과 달리 게임 삭제 시 제거하지 않습니다. 게임 삭제는 플레이 기록만 지웁니다.

추가 Datasets/ 및 fakeLevels.json은 저장소에 없어 자동 복원되지 않습니다. 기본 24개 이외 데이터 도입은 별도 검토/등록이 필요합니다. 공개 배포 전에 영상의 출처·사용권·재배포 조건을 팀에서 확인해야 합니다. 로컬 등록은 권한 확인을 대신하지 않습니다.

## 결과 해석

- 분석 demo: 영상 형식 확인 + 고정 공간/시간 점수. 실제 얼굴 검출이나 XAI가 아닙니다.
- 게임 정답: 데이터셋 라벨. AI 판정을 정답으로 사용하지 않습니다.
- 실제 AI 평가가 없으면 비교값은 null/준비 안 됨입니다. 미평가 항목을 오답이나 0점으로 집계하지 않습니다.
- 콤보: 정답 100 + 직전 연속 정답 수 × 10. 서버가 계산하며 클라이언트 점수를 믿지 않습니다.
- 응답 시간: 브라우저가 보고한 참고 통계이며 경쟁 랭킹의 신뢰할 수 있는 시간 증거가 아닙니다.
- 영상 오류로 건너뛴 문항은 정답/오답과 별도로 표시하고 실제 답한 수를 정확도 분모로 사용합니다.

## 실제 모델

```powershell
.\Start-Service.ps1 -Mode real -ModelDir 'C:\path\to\deepfake_detector' -WorkerPython 'C:\path\to\model-env\Scripts\python.exe'
```

모델용 의존성/가중치는 기존 MODEL.md와 requirements-model-lock.txt를 따릅니다. 팀의 다른 연구 모델이 자동으로 호환되는 것은 아닙니다. 실행기는 모델을 내려받거나 demo를 real로 위장하지 않습니다. 게임 모델 평가는 별도 명시적 작업이며, 평가 버전이 변경돼도 완료된 게임의 결과를 바꾸면 안 됩니다.

게임 AI 비교를 활성화하려면 백엔드 폴더에서 모델 의존성이 설치된 Python으로 아래 명령을 실행합니다. 실제 추론 비용이 발생하므로 자동 실행하지 않습니다. 실패한 영상에 임의 점수를 넣지 않으며, 이미 평가한 영상은 건너뜁니다. 새 모델로 다시 평가할 때 `--force`를 명시합니다.

```powershell
& 'C:\path\to\model-env\Scripts\python.exe' -m app.evaluate_games --model-dir 'C:\path\to\deepfake_detector' --clip-id real_001
# 모든 등록 영상: --clip-id 생략. 결과는 이후 생성한 게임에만 반영됩니다.
```

이번 통합 검증은 demo 분석과 실제 게임 영상 재생을 대상으로 했습니다. 위 실제 모델 평가 CLI는 검증된 결과 저장/거부 단위 테스트만 수행했으며, 전체 24개 영상의 실제 추론이나 탐지 정확도를 검증한 것은 아닙니다.

## 검증 명령

```powershell
cd deeptector-backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\ruff.exe check app scripts tests
# 통합 서버와 기본 24개 게임 영상 등록 후 별도 터미널
.\.venv\Scripts\python.exe scripts\smoke_integrated.py
cd ..\frontend
npm run lint
npm run build
```

HTTP smoke는 자체 세션/합성 영상으로 테스트하며 만든 게임과 분석을 삭제합니다. 실제 개인 영상을 사용하지 않습니다. 화면에서의 검증과 모델 정확도 평가는 별도이며, 합성 영상 smoke 통과는 탐지 정확도를 의미하지 않습니다.
