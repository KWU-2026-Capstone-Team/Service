# 다른 Windows 노트북에서 VeriVid 시연하기

이 폴더는 **`kanghyeon/verivid-android` 브랜치의 Android 앱 + 기존 Service 서버**를 전시용 노트북에 준비한다. main 병합·Vercel 배포·외부 공개 서버 설정은 하지 않는다.

## 처음 한 번: 인터넷이 필요함

Windows 10/11 Intel/AMD 64비트 기준이다. [Git for Windows](https://git-scm.com/downloads/win)와 **Python 3.12 64비트**를 먼저 설치한다. Python은 [3.12.10 공식 페이지](https://www.python.org/downloads/release/python-31210/)의 Windows installer (64-bit)를 사용하면 된다. 다른 프로젝트의 Python이나 가상환경을 삭제할 필요는 없다.

PowerShell에서 쓰기 가능한 **영문 경로**로 받는다. 예:

```powershell
git clone --branch kanghyeon/verivid-android --single-branch https://github.com/KWU-2026-Capstone-Team/Service.git C:\VeriVidService
cd C:\VeriVidService\exhibition
.\Setup-Exhibition.cmd
```

`C:\`에 쓸 권한이 없다면 `C:\Users\Public\Documents\VeriVidService` 등 쓰기 가능한 영문 경로를 사용한다. 폴더 하나만 다운로드하지 말고 **브랜치 전체를 clone**한다. 기존 main 체크아웃을 초기화하거나 덮어쓸 필요가 없다.

설치 스크립트는 별도 Python 환경, 고정 버전 라이브러리, 기존 백엔드 소스 사본, 팀 모델 11개와 게임 영상 24개, Android 연결 도구를 준비한다. 모델/영상은 팀 원본 저장소의 **검증된 커밋**에서 가져오고 재업로드하지 않는다. 처음에는 수백 MB 이상의 다운로드와 여유 디스크 공간 6GB 이상이 필요하다. 재실행은 기존 기록을 삭제하지 않는다. 실패하면 오류를 해결한 뒤 다시 실행한다.

기존 체크아웃/가상환경이 다른 경로에서 옮겨진 경우에는 그대로 쓸 수 있다고 가정하지 말고 새 영문 경로에 clone하여 설치한다. Windows DLL 오류가 나면 `third-party/VC_redist.x64.exe`를 설치한 뒤 다시 시도한다. 설치 과정에서 서버를 자동 실행하거나 방화벽을 열지 않는다.

## 매번 시연할 때

1. `Start-Exhibition.cmd` → 서버/API와 실제 모델 worker 시작 (`READY` 확인).
2. 아래 두 가지 중 하나를 준비한다.
   - **노트북만 사용:** Android Studio에서 에뮬레이터를 부팅한다. Android Studio/AVD는 이 저장소에 포함되어 있지 않다.
   - **실제 Android 휴대폰 사용:** USB 디버깅을 켜고 데이터 전송 USB 케이블로 연결한 뒤 휴대폰에서 컴퓨터를 허용한다. 에뮬레이터는 필요 없다.
3. `Connect-Android.cmd` → 포함된 APK 설치/업데이트, USB/에뮬레이터 포트 연결, 앱 실행.
4. 시연 후 `Stop-Exhibition.cmd` → 해당 폴더에서 실행한 서버만 종료.

앱은 Windows 프로그램이 아니라 Android 앱이다. **에뮬레이터와 휴대폰이 모두 없으면 노트북에서 APK를 직접 실행할 수 없다.** 휴대폰 방식은 Android Studio 없이 가능하다. 에뮬레이터와 CPU 추론을 동시에 돌리면 메모리 부담이 커서 USB 휴대폰 시연을 권장한다.

서버는 이 노트북의 `127.0.0.1`에만 열리고 앱은 `adb reverse`로 연결한다. 전시장 와이파이·공개 IP·Vercel은 앱↔서버 연결에 필요 없다. 다만 다른 앱의 온라인 영상을 재생하는 데 필요한 인터넷까지 대신 제공하는 것은 아니다. USB를 뽑거나 기기를 재부팅하면 연결 스크립트를 다시 실행한다.

## 기능과 데이터

- 첫 화면의 **영상 분석 / 판별 게임** 선택을 유지한다.
- 녹화 또는 저장된 동영상 → 동의 후 실제 모델 분석 → 행동 신호등·실제 점수·Grad-CAM.
- 게임 → 난이도·문제 수 → 영상 판별 → 서버 채점·해설·AI 비교 → 결과 리포트.
- 이미지 분석은 현재 팀 모델 API가 지원하지 않아 준비 중 안내만 한다.
- 게임은 전체 난이도/5문제를 우선 사용한다. 일부 난이도 10/15문제는 영상 부족 안내가 정상이다. 뒤로 가면 게임 설정으로 돌아간다.
- 게임 AI 비교값은 원래 모델로 측정된 24개 평가만 복원하며 모델과 영상 SHA-256을 확인한다. 가짜 점수나 사용자 정답 기록은 만들지 않는다.
- 개발 PC의 사용자 DB/토큰/업로드/게임 기록은 포함하지 않았다. 형 노트북에서 새 `runtime-data/`가 생성된다.

모델/영상/가상환경/DB/로그/Android 도구는 Git에서 제외한다. 원본 영상과 가중치의 공개 재배포·전시 사용 권한은 팀에서 확인해야 한다. **생성된 폴더를 `git add -f`로 올리거나 공개 배포하지 않는다.** APK는 내부 시연용 debug 서명이며 Play Store용 release가 아니다.

## 점검 및 제한

`Check-Exhibition.cmd`로 실행 환경과 모델 식별자를 확인한다. `Start-Exhibition.ps1 -Port 18000`처럼 다른 포트를 썼다면 `Connect-Android.ps1 -Port 18000`으로 동일 포트를 지정한다. 다른 앱의 서버를 임의 종료하지 않는다.

기존 전시 패키지는 별도 경로에서 실제 CPU 분석·히트맵 4장·게임 5문제 및 에뮬레이터 연결을 검증했다. 이 GitHub 설치 경로의 추가 검증 결과는 `SETUP-VERIFICATION.md`를 확인한다. **형 노트북의 실제 속도·드라이버와 실제 USB 휴대폰의 녹화 전체 흐름은 현장 리허설이 필요하다.** 성능이나 탐지 정확도를 보장하지 않는다.
