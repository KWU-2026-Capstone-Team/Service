# DeepTector 서비스

이 저장소의 `main`은 DeepTector UI 프로토타입과 서비스 백엔드를 관리합니다. 서비스 구현은 `anseonghwan/deeptector`의 `service/prototype-backend`에서 가져왔으며, 모델 연구/학습 코드와 가중치는 포함하지 않습니다. 기존 서비스 방향 결정 회의 PDF는 그대로 보존합니다.

## 포함된 파일

| 경로 | 내용 |
|---|---|
| `deeptector-backend/` | FastAPI, SQLite DB 스키마, 비동기 worker, 모델 adapter, 게임 API, 테스트 |
| `deeptector-backend/docs/` | API/OpenAPI, DB 관계, 모델 정책, 검증 문서 |
| `prototype/prototype-version.html` | 팀 공유용 11개 화면 정적 프로토타입 |
| `design/deeptector.pen` | UI 디자인 원본 |
| `exports/deeptector-screens/` | 화면별 PNG 11장 |

모델 가중치, 연구 실험, 학습 데이터, 가상환경, 업로드 영상, 로컬 DB, 작업 로그 및 인증정보는 포함하지 않습니다. `requirements-model*.txt`와 `app/inference.py`는 외부 모델을 실행하기 위한 서비스 의존성/adapter이므로 포함합니다.

## 시작하기

```powershell
git clone --branch main --single-branch https://github.com/KWU-2026-Capstone-Team/Service.git
cd Service
```

화면은 `prototype/prototype-version.html`을 브라우저로 열어 확인합니다. 이는 정적 시연 파일이며 API가 자동 연결되어 있지는 않습니다.

백엔드 실행은 [실행 안내](deeptector-backend/README.md)를 따르세요. 모델 파일 없이도 명시적인 `demo` 모드로 API 흐름을 확인할 수 있습니다. demo 응답은 실제 탐지 결과가 아닙니다.

## 외부 모델 연결

현재 adapter는 별도 `AI-Generated-Media-Detection/deepfake_detector`의 **ConvNeXt 5개 + MC3-18 6개** 구현을 대상으로 합니다. `anseonghwan/deeptector` 저장소 `main`의 연구 모델과 자동 호환된다는 의미가 아닙니다.

실제 추론에는 신뢰된 외부 모델 폴더와 가중치가 필요하며 API/worker 환경에 `DEEPTECTOR_MODEL_DIR`를 설정하세요. 예를 들어 이 체크아웃과 `AI-Generated-Media-Detection` 폴더가 같은 상위 디렉토리에 있는 경우, 저장소 루트에서 다음과 같이 설정합니다.

```powershell
$env:DEEPTECTOR_MODEL_DIR=(Resolve-Path '..\AI-Generated-Media-Detection\deepfake_detector').Path
```

다른 환경에서는 `infer.py`, `xai.py`, `models/spatial/*.pt`, `models/temporal/*.pt` 및 `calibration.json`이 있는 외부 폴더의 절대 경로를 지정합니다. 모델 코드를 포함하지 않는 서비스 브랜치이므로 **이 브랜치만 clone해서 실제 추론을 할 수는 없습니다**.

## 구현 및 검증 범위

영상 업로드/상태/결과/XAI/삭제, 세션별 접근 권한, 대기열/업로드 제한, 만료 정리, AI vs 사람 게임 API를 제공합니다. DB 구현은 SQLite이며 PostgreSQL은 이식 계획을 문서화했습니다. 게임에는 팀이 확인한 정답 영상과 출처/사용권을 등록해야 합니다. 노랑 신호등은 시범 정책입니다.

이관 전 39개 자동 테스트와 실제 외부 가중치를 사용하는 업로드→분석→이미지 조회→삭제 통합 검증을 통과했습니다. 재현 방법과 성능 검증 한계는 [검증 기록](deeptector-backend/docs/VERIFICATION.md)을 참고하세요.

- [API 명세](deeptector-backend/docs/API.md)
- [OpenAPI JSON](deeptector-backend/docs/openapi.json)
- [DB 스키마](deeptector-backend/docs/DATABASE.md)
- [모델 output 및 신호등](deeptector-backend/docs/MODEL.md)
- [frontend API 연동 예제](deeptector-backend/examples/client.js)
