# VeriVid 실제 모델 연결 — 2026-11-05

## 연결 구조와 원본 규칙

Android(Kotlin/Compose) → 기존 팀 FastAPI → 기존 worker → PyTorch 공간 5개 + 시간 6개 앙상블.
모델을 APK 안에 넣거나 학습·변환한 것이 아니다. 서버/모델 원본 추적 파일은 수정하지 않았고 GitHub push도 하지 않았다.

확인한 원본:

- [AI-Generated-Media-Detection / seelhwan](https://github.com/KWU-2026-Capstone-Team/AI-Generated-Media-Detection/tree/seelhwan), commit `4d2582f1eb9af83b86bcd8ffee96c5670af29e0b`
- [Service / main](https://github.com/KWU-2026-Capstone-Team/Service), commit `3a44aa340c96421dccf33dec0ee52914a74e333d`
- Service README, INTEGRATION_CONTRACT.md, backend README, docs/API.md, docs/MODEL.md, schemas/inference/config 코드의 계약을 확인했다.
- 별도 modeling/ashwan의 AGENTS.md, PROJECT_STATUS.md, CODEX_HANDOVER.md도 읽었다. 해당 주파수 기반 연구 모델은 현재 서비스 adapter와 다른 파이프라인이므로 섞지 않았다.
- 실제 사용한 모델/서비스 브랜치에는 추가 AGENTS.md가 없었다. 모델·데이터·임계값·보정값을 임의 변경하지 않았다.

## 다운로드 위치

워크스페이스 `C:\Users\user\Documents\ChatGPT\졸작` 기준:

| 위치 | 내용 |
|---|---|
| `integrations/AI-Generated-Media-Detection/deepfake_detector` | 읽기 전용 모델 checkout 및 11개 가중치(합계 416,751,518 bytes) |
| `integrations/Service/deeptector-backend` | 기존 팀 백엔드 checkout |
| `integrations/Service/deeptector-backend/.venv-model` | 분리된 Python 3.12 CPU 실행 환경 |
| `integrations/local-runtime` | 실행 로그·PID 정보·테스트 DB·서버 산출물·시험 영상 |
| `integration-backup-20261105` | 변경 전 Android src/Gradle/README 복사본 |

모델과 서버는 Android 소스/assets에 복사하지 않았다. APK에는 모델 가중치가 포함되지 않는다.

## 실행 / 종료

워크스페이스 루트 PowerShell에서:

```powershell
.\integrations\Start-ModelServer.ps1
# 종료
.\integrations\Stop-ModelServer.ps1
```

이미 8000 포트를 사용 중이면 시작하지 않고 오류를 표시한다. 종료 스크립트는 기록된 실행 파일과 명령이 일치하는 프로세스 트리만 종료하며 모델·DB는 삭제하지 않는다. 실행 도중 종료한 작업은 다음 시작 시 기존 서버의 WORKER_RESTARTED 복구 정책을 따른다.

- PC API: `http://127.0.0.1:8000`
- 에뮬레이터 앱: `http://10.0.2.2:8000/`
- 외부 네트워크에 공개하지 않고 loopback에만 바인딩한다.
- 실제 모드 `real`, OMP/MKL/OpenBLAS 스레드 2개, 추론 제한 시간 600초.
- API 준비와 모델 폴더 존재만으로 실제 추론 성공을 보증하지 않는다. 실패는 그대로 앱에 표시한다.
- 모델·원본 서버 코드는 그대로이며 환경 변수만 지정한다. 백엔드 DB는 기존 구현을 사용하며 신규 DB 구조를 만들지 않았다.

처음 다른 PC에 준비할 때는 원본 저장소/브랜치를 위 commit 기준으로 가져오고, backend 디렉터리에서 `py -3.12 -m venv .venv-model`, `.venv-model\Scripts\python -m pip install -r requirements-model.txt`를 실행한다. 이번 환경에서 `pip check` 통과. 원본의 검증 기준 Python은 3.11이며 이번 PC는 설치되어 있던 3.12 CPU 환경에서 실행 검증했다.

## 앱 동작

1. 기존 MediaProjection/플로팅 버튼 녹화 로직은 유지한다.
2. 녹화 파일 또는 파일 선택기의 영상을 준비하고 재생할 수 있다.
3. 분석 전 전송 대상 주소와 개인정보 주의 문구를 표시하고 동의를 받는다.
4. `/health/ready`, `/api/v1/model-info` 확인. demo 서버는 실제 결과로 취급하지 않는다.
5. 익명 세션 → Bearer 인증 → multipart `video` 업로드(202).
6. 2초 간격으로 QUEUED/PROCESSING/COMPLETED/FAILED와 실제 stage 조회. 임의 %는 표시하지 않는다.
7. 서버 `policy.traffic_light`의 GREEN/YELLOW/RED를 기존 행동 신호등에 대응시킨다. Android에서 임계값을 새로 계산하지 않는다.
8. Spatial/Temporal/Fused 점수, 실제 사용 얼굴 프레임, 원본 크롭과 Grad-CAM 표시.
9. 서버 기록을 날짜별로 조회. 결과가 없는 목록 응답에 위험 단계를 임의로 만들지 않는다. 현재 불러온 결과 외에는 ‘분석 완료 · 결과 보기’로 표시하며 열 때 실제 결과를 조회한다.

세션 토큰은 Android Keystore AES-GCM 암호화 저장, 앱 백업 제외. 인증 헤더는 동일 서버의 정해진 경로에만 보내며 redirect를 따르지 않는다. POST는 자동 재전송하지 않으며 GET만 연결 복구 재시도를 허용한다. 401이면 토큰을 폐기하고 안내한다. 새 세션에서는 이전 세션의 기록을 복구할 수 없다.

처리 중 화면을 나가도 서버 작업은 계속된다. 서버에 분석 취소 API가 없어 ‘서버 분석 취소’ 기능을 꾸며 넣지 않았다. 진행 중 작업 ID는 기기에 저장해 앱 재시작 시 조회를 재개한다. 업로드 응답을 받기 전 프로세스가 종료되면 작업 ID를 저장하지 못할 수 있으므로 기록에서 확인한다.

## 모델이 제공하지 않는 것

- 이미지 분석 API → 이미지 버튼은 지원 준비 중 안내만 표시. 사진을 반복 영상으로 몰래 변환하지 않는다.
- 눈/입 개별 위험 점수, confidence, 구간별 위험도 그래프, 의심 구간 시작·종료 → 기존 예시 수치를 제거했다.
- 프레임 timestamp는 얼굴 샘플 추출 시점이지 위험 구간이 아니다. 시간축 전체 검사나 사람 추적이 아니다.
- Grad-CAM은 모델 주목 영역이지 조작의 물증이 아니다.
- GREEN은 ‘진짜 인증’, RED는 ‘딥페이크 확정’으로 표현하지 않는다.
- YELLOW는 팀 서비스의 시범 정책이며 검증된 최적 임계값으로 표현하지 않는다.

## 빌드와 테스트

이번 Windows의 한글 경로에서는 Gradle의 JUnit worker가 class를 찾지 못했다. 소스는 이동하지 않고 출력 폴더만 ASCII 경로로 지정하여 해결했다. 기존 설치 앱과 동일한 debug key를 사용하여 **삭제 없이** 업데이트했다.

```powershell
# VeriVidAndroid 디렉터리에서 JDK 17 설정 후
.\gradlew.bat assembleDebug testDebugUnitTest `
  '-PverividBuildRoot=C:/Users/user/AppData/Local/VeriVidBuild/model-integration' `
  '-PverividDebugKeystore=C:/Users/user/.android/debug.keystore' --console=plain
```

빌드·JUnit 12개 통과. 빌드 출력은 `C:\Users\user\AppData\Local\VeriVidBuild\model-integration`, 배포용 복사본은 `VeriVidAndroid/outputs/VeriVid-model-debug.apk`.

자동 테스트: 세 가지 서버 신호등 매핑, 실제 점수/샘플 파싱, demo 구분/거절, policy 누락·점수 범위·융합 불일치·결과 누락 차단, 얼굴 부족 오류, 세션/업로드/poll 계약, 타 서버 artifact 차단, 만료 토큰 폐기.

## 실제 확인 결과

- U_Pixel_2 에뮬레이터에서 시스템 파일 선택 → 영상 확인 → 전송 동의 → 실제 CPU 추론 → 신호등 결과 성공.
- 원본 저장소가 검증에 사용한 `05-xai-detail.png`를 16프레임 MP4로 인코딩한 **연결 시험용 영상**을 사용했다. 사용자 미디어나 학습용 평가셋을 임의로 수집하지 않았다.
- Spatial `0.013446978782303632`, Temporal `0.4708164072206988`, Fused `0.2421316930015012`, 얼굴 16개, GREEN.
- `is_demo=false`, 모델 `dual-branch-d28bda40d902b7d0`, calibration `d94834235df539b1`.
- 서버 응답 점수가 Android에 24/100, 1/100, 47/100으로 표시됨을 확인했다(정수 축약).
- 인증된 Grad-CAM 2장·원본 2장 조회. 최초 이미지 로딩 실패가 있어 GET 연결 복구 재시도와 수동 재시도를 적용한 뒤 표시를 다시 확인했다.
- APK 재설치(업데이트)/프로세스 재시작 뒤 세션 기록 복원 → 날짜 펼침 → 결과 재조회 성공.
- 얼굴 없는 회색 영상을 실제로 업로드하여 `INSUFFICIENT_FACE_DATA`, 0/4 안내를 확인. GREEN이나 더미 결과로 대체되지 않았다.
- 이미지 버튼은 지원 준비 중 안내 표시. 앱 crash buffer 비어 있음.
- 실제 녹화/플로팅/권한 처리 코드는 변경하지 않았다. 이번 작업에서는 실제 기기의 다른 앱 녹화부터 분석까지 전체 흐름을 재검증하지 않았다.
- 탐지 정확도, 일반화, 실사용 처리량, 다양한 기기/화면 회전/큰 글꼴 조합은 검증하지 않았다. 정지화면 반복 영상의 결과는 모델 성능 근거가 아니다.

스크린샷: `preview/model-integration-result.png`, `model-integration-details.png`, `model-integration-xai.png`, `model-integration-error.png`.

## 변경 파일

수정: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/file_paths.xml`, `ui/VeriVidApp.kt`, `ui/HomeWorkspace.kt`, `README.md`.

추가: `data/AnalysisModels.kt`, `DeeptectorApi.kt`, `EncryptedSessionStore.kt`, `AnalysisController.kt`, `ui/ConnectedAnalysisScreens.kt`, main/debug `network_security_config.xml`, `AnalysisContractTest.kt`, 이 문서. 워크스페이스 integrations에 로컬 실행/종료/시험 영상 생성 도구를 추가했다.

원본 `CaptureOverlayService.kt`, `MainActivity.kt`의 녹화 로직, 홈 그래픽/버튼 구조, 신호등 정렬과 기존 테마는 유지했다. 메인 데이터가 더미에서 실제 서버 응답으로 바뀌므로 결과·상세·기록의 데이터 바인딩과 실제 모델 범위를 설명하는 문구는 수정했다.

## 팀 서버로 교체

`-PverividApiBaseUrl=https://실제팀서버/`로 다시 빌드한다. 호스트 변경 시 세션도 별도로 발급한다. 앱에 비밀키를 넣지 않는다. 현재 연결한 API 계약을 바꿀 경우 `DeeptectorApi`/`AnalysisJson`과 contract tests를 함께 수정한다.

실제 휴대폰에서 PC 로컬 서버를 쓸 때는 debug 빌드를 `http://127.0.0.1:8000/`로 구성하고 `adb reverse tcp:8000 tcp:8000`을 사용한다. LAN으로 무인증 서버를 공개하지 않는다. release는 HTTPS 서버가 필요하며 로컬 HTTP는 허용하지 않는다. APK를 복사하는 것만으로 PC 서버/모델까지 다른 기기로 전달되지는 않는다.
