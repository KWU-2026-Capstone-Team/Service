# Service 저장소 Android 앱 인계

이 브랜치는 `Service/main`의 기존 파일을 변경하지 않고 `VeriVidAndroid/`와 로컬 연결 보조 스크립트만 추가한다. main 병합이나 배포는 수행하지 않는다.

## 앱 열기 및 빌드

Android Studio에서 저장소 루트가 아닌 `VeriVidAndroid` 폴더를 연다. JDK 17과 Android SDK 34를 사용한다. `local.properties`는 각 PC의 SDK 경로로 Android Studio가 생성하며 Git에 올리지 않는다.

```powershell
cd VeriVidAndroid
.\gradlew.bat assembleDebug testDebugUnitTest
```

Windows 한글 경로에서 테스트 worker 오류가 나면 ASCII 출력 경로를 지정한다.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest '-PverividBuildRoot=C:/VeriVidBuild/android'
```

기본 개발 API 주소는 Android 에뮬레이터에서 PC를 가리키는 `http://10.0.2.2:8000/`이다. 팀 서버 주소는 빌드 시 지정한다.

```powershell
.\gradlew.bat assembleDebug '-PverividApiBaseUrl=https://YOUR-TEAM-SERVER/'
```

실제 기기와 에뮬레이터의 서버 주소는 다르다. 실제 기기의 USB 연결 테스트 및 HTTPS 배포 조건은 [모델 연결 문서](model-integration.md)를 확인한다. APK만 설치해도 서버와 모델이 함께 설치되는 것은 아니다.

## 서버와 데이터

이 브랜치의 Android 앱은 기존 Service API를 사용한다. 백엔드·모델·게임 원본은 변경하지 않았다. 모델 가중치, 데이터셋, 동영상, DB, 세션 토큰, Python 환경, 서명키 및 빌드 APK는 업로드하지 않는다.

기존 백엔드 실행은 저장소 루트의 `deeptector-backend/README.md`를 따른다. 앱은 실제 모델 모드를 요구하며 demo 점수를 실제 분석 결과로 바꾸어 표시하지 않는다. 게임의 정답과 채점은 서버가 담당하고, 평가가 없는 AI 결과는 미평가로 표시한다.

`integrations/`의 보조 스크립트는 개발 PC의 별도 체크아웃 구조를 전제로 한다. 이 저장소가 이미 Service 소스를 포함한다고 해서 그 스크립트가 자동으로 루트 백엔드를 사용하는 것은 아니다. 사용할 경우 다음 구조와 별도 Python 환경을 먼저 준비한다. 원본 커밋과 설치 방법은 [모델 연결 문서](model-integration.md), [게임 연결 문서](game-integration.md)에 있다.

```text
integrations/
  Service/deeptector-backend/.venv-model/
  AI-Generated-Media-Detection/deepfake_detector/
  deepGame-reference/
  local-runtime/               # 실행 시 생성, Git 제외
```

## 검증과 최신 수정

- Android debug 빌드 및 계약 테스트 25개(분석 12, 게임 13) 통과.
- 에뮬레이터에서 실제 모델 결과·히트맵 조회, 게임 생성·영상 재생·서버 채점·결과 리포트·진행 복원 확인.
- `GAME_DATASET_NOT_READY` 영상 부족 안내에서 상단 및 Android 뒤로가기는 앱 홈이 아닌 게임 난이도·라운드 선택 화면으로 돌아간다. 에뮬레이터에서 두 경로를 확인했다.
- 실제 휴대폰의 다른 앱 녹화부터 분석까지 전체 흐름, 다양한 기기 및 네트워크 조합, 탐지 정확도와 교육 효과는 별도 검증 대상이다.

이전 디자인 및 연동 문서는 당시의 작업 기록이다. 그 문서의 PC 절대 경로, 로컬 APK·스크린샷 위치 및 'push하지 않았다' 문구는 해당 작업 시점의 기록이며 모든 산출물이 이 브랜치에 포함된다는 뜻은 아니다. 현재 인계 범위와 실행 방법은 이 문서 및 앱 README를 우선한다.
