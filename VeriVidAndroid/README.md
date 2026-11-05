# VeriVid Android

Service 저장소의 별도 Android 브랜치 인계·빌드 방법은 [팀 인계 안내](docs/service-handoff.md)를 먼저 확인하세요. Android Studio에서는 이 `VeriVidAndroid` 폴더를 엽니다.

Kotlin + Jetpack Compose로 구현한 네이티브 Android 앱입니다. 화면 녹화와 플로팅 버튼도 Kotlin 네이티브 계층에서 동작합니다. `app/src/main/assets`의 기존 웹 파일은 디자인 참고용으로만 남아 있으며 앱 실행 화면에서는 사용하지 않습니다.

## Android Studio 실행

1. Android Studio에서 이 `VeriVidAndroid` 폴더를 엽니다.
2. SDK Manager에서 Android 14(API 34) SDK와 Build Tools를 설치합니다.
3. Gradle JDK는 17을 선택합니다.
4. 실제 Android 기기에서 실행합니다. 오버레이와 MediaProjection 동작은 실제 기기 테스트를 권장합니다.

## 사용자 흐름

앱 실행 시 `영상 분석 / 판별 게임` 선택 화면이 먼저 열립니다. 영상 분석은 기존 녹화·파일 분석 화면으로, 판별 게임은 Android 네이티브 게임 화면으로 이동합니다.

1. 홈에서 `화면 분석 시작`을 누릅니다.
2. 안내 화면에서 `화면 녹화 준비`를 누릅니다.
3. 다른 앱 위 표시 권한과 Android 화면 캡처 권한을 허용합니다.
4. 다른 앱에서 오른쪽의 원형 VeriVid 버튼을 누릅니다.
5. `녹화 시작`을 누르면 REC와 시간이 표시됩니다.
6. 다시 버튼을 열어 `녹화 중지`를 누릅니다.
7. VeriVid로 돌아와 녹화 파일을 확인합니다.
8. `이 영상 분석하기`에서 서버 전송에 동의하면 실제 모델 API에 업로드합니다.
9. 서버 상태를 조회한 뒤 서버의 신호등 정책과 실제 점수·Grad-CAM을 표시합니다. 실패 시 더미로 대체하지 않습니다.

홈의 `동영상 선택`으로 시스템 파일 선택기를 열 수 있습니다. 현재 팀 모델은 영상만 지원하므로 `이미지 선택`은 지원 준비 중 안내를 표시합니다.

## 녹화 파일 위치

앱 전용 외부 저장소 아래에 저장됩니다.

```text
/storage/emulated/0/Android/data/com.verivid.app/files/Movies/VeriVid/video_capture_YYYYMMDD_HHMMSS.mp4
```

앱 내부에 모델을 탑재하지 않습니다. 기존 팀 FastAPI/worker가 PC에서 PyTorch 모델을 실행하며, 앱은 선택한 영상만 동의 후 전송합니다. 파일 선택기의 복사본은 앱 캐시 `analysis-input/`에 저장하고 다른 영상을 선택하면 이전 복사본을 삭제합니다. 녹화 원본은 앱 전용 Movies 폴더에 남습니다.

## 백엔드 연결 지점

2026-11-05에 팀 저장소의 실제 모델/API 규격에 연결했습니다.

- `data/DeeptectorApi.kt`: 세션, multipart video 업로드, 상태/기록 조회, 인증된 이미지 다운로드
- `data/AnalysisModels.kt`: 실제 응답 파싱 및 유효성 검사
- `data/AnalysisController.kt`: 입력 준비, 업로드, 2초 간격 polling, 오류/복귀 흐름
- `data/EncryptedSessionStore.kt`: Android Keystore로 세션 토큰 암호화, 백업 제외
- `ui/ConnectedAnalysisScreens.kt`: 실제 결과/프레임/히트맵 UI
- API 주소: Gradle `-PverividApiBaseUrl=https://팀서버주소/` (기본 개발값 `http://10.0.2.2:8000/`)

로컬 서버 실행·종료, 빌드, 검증 결과와 제한은 [모델 연동 안내](docs/model-integration.md)를 확인하세요. 실제 기기의 화면 녹화/오버레이 전체 흐름 및 탐지 성능은 별도로 검증해야 합니다.

## 판별 게임

팀 deepGame의 영상·해설과 기존 Service 게임 API를 사용합니다. WebView나 외부 브라우저를 띄우는 방식이 아니라 Compose + Android VideoView로 구현했습니다.

- 난이도 전체/쉬움/보통/어려움, 5/10/15문제
- 앱 내 음소거 영상 재생, 다시 보기 1회, 확신도 및 REAL/FAKE 답변
- 서버 채점·콤보·정답/해설·실제 AI 판단 비교·결과 리포트
- 진행 중 게임 이어하기, 최근 게임 결과 재조회
- 얼굴 영상은 기기 비공개 캐시에 임시 저장하며 정답 목록은 APK에 포함하지 않음

현재 GitHub에 포함된 기본 영상은 **24개**입니다. 이 PC에서는 24개 모두 팀의 실제 모델로 평가했습니다. 별도 Datasets 영상은 저장소에 포함되어 있지 않으며 임의로 다운로드하지 않았습니다. 일부 난이도의 10/15문제는 영상 수가 부족할 수 있어 서버가 409로 안내합니다.

준비·검증 및 변경 파일은 [게임 연동 안내](docs/game-integration.md)를 확인하세요.
