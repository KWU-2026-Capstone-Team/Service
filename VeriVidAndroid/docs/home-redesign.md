# VeriVid 홈 리디자인 · 1차 작업

## 범위

Kotlin + Jetpack Compose + Material 3 프로젝트 유지. 홈만 변경한다.
기록, 결과, 상세 분석, 도움말, 하단 메뉴, 공통 테마, MediaProjection 녹화 로직은 변경하지 않았다.
다른 화면 코드, 공통 헤더/메뉴, Theme.kt, MainActivity.kt, CaptureOverlayService.kt,
VeriIcons.kt, build.gradle.kts의 작업 전후 해시가 일치함을 확인했다.

## 설계

- 홈을 소개/장식 화면이 아닌 입력 출처 선택 작업 공간으로 구성.
- 헤더: VeriVid + 보기 설정 버튼. 홈에서만 크게 보기 스위치를 Material 바텀시트로 이동.
- 제목: '무엇을 확인할까요?' 한 문장. 기본 24sp/32sp, 중간 굵기.
- 가장 중요한 화면 녹화 액션: 얕은 인디고 면 전체가 클릭 영역. 중첩 버튼 없음.
- 저장 파일: 아이콘·제목·설명·이동 표시를 가진 두 개의 리스트 행.
- 조용한 참고 안내: 분석 결과의 한계를 알리고 원본 출처 확인 권장.
- 이전 요청대로 최근 기록 미리보기는 재추가하지 않음. 하단 기록 메뉴에서 접근.
- 홈 전용 색/타이포 토큰. 다른 화면의 MaterialTheme에는 적용하지 않음.
- 간격: 8/16/24dp 중심. 좌우 24dp, 360dp 미만은 16dp. 작은 화면은 그룹 간격도 16dp로 축소. 최대 콘텐츠 너비 560dp.
- 16dp 기본 모서리, 무그림자, 무그라데이션, 무반투명. 명도 대비를 유지.
- 리스트 터치 영역 기본 72dp/작은 화면 64dp, 설정 버튼 48dp. 텍스트는 시스템 배율을 그대로 따름.
- 큰 글씨에서 행 높이를 고정하지 않으며 홈과 설정 시트 모두 스크롤 가능.

## 레퍼런스 적용 판단

- Google Photos 공식 제품 소개: https://www.google.com/photos/about/
  - 입력/미디어 유형 구분과 빠른 탐색 원리를 참고. 서비스 소개 배너는 복사하지 않음.
- Apple Health 공식 소개: https://www.apple.com/health/
  - 요약과 추가 설명의 분리 원리만 검토. 결과 화면 리디자인은 이번 범위에서 제외.
- Material 3 버튼: https://m3.material.io/components/buttons/guidelines
- Compose 접근성: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
  - 의미 있는 액션 역할, 설정 스위치 상태, 48dp 이상의 터치 영역.

## 파일

- 추가: `app/src/main/java/com/verivid/app/ui/HomeWorkspace.kt`
  - 홈 전용 테마·헤더·설정 시트·주 액션·파일 행·안내.
- 수정: `app/src/main/java/com/verivid/app/ui/VeriVidApp.kt`
  - Home 경로에서만 새 헤더와 홈을 사용. 기존 콜백 유지.
- 대체/제거: `app/src/main/java/com/verivid/app/ui/HomeGlass.kt`
  - 이전 홈 전용 유리빛 스킨을 새 작업 공간으로 대체. 다른 화면에서 사용하지 않던 파일.

## 기존 기능의 한계 (이번 단계에서는 미변경)

화면 분석 시작은 기존 녹화 안내로 이동하고 기존 권한/서비스 흐름을 유지한다.
영상/사진 선택 버튼은 현재 실제 Android 파일 선택기를 열지 않고 기존 더미 분석으로 이동한다.
이번 작업은 홈 리디자인이므로 URI/파일 처리, 분석 콜백, 백엔드/API, 시연 결과 값을 변경하지 않았다.
실제 파일 선택기와 파일 검증은 별도의 기능 구현 범위가 필요하다.
새 홈에 가짜 모델 신뢰도, 위험 점수, 가짜 최근 기록은 추가하지 않았다.

## 검증

첫 APK 빌드·설치 및 411dp 화면 실제 렌더링을 확인했다.
첫 화면 검토 후 주 액션 설명을 자연스러운 문장으로 고치고 설정 시트 스크롤을 보완했다.
최종 검증 결과:

- 최종 APK 빌드·설치 성공, 크래시 로그 없음.
- 411dp 기본 화면: 화면 분석·영상·사진 세 액션 모두 표시.
- 343dp + 시스템 글꼴 130%: 첫 화면에서 세 액션 모두 표시.
  첫 검토에서 사진 설명이 아래로 내려간 점을 보완했고, 최종 사진 행 전체가 스크롤 viewport 안에 들어옴을 확인.
- 480dp 큰 스마트폰: 세 액션 표시, 좌우 정렬 및 텍스트 잘림 없음.
- 411dp + 글꼴 150%: 자연스러운 줄바꿈과 스크롤.
- 글꼴 150% + 앱 크게 보기 ON: 설정 토글·완료 버튼·사진 행에 스크롤로 접근 가능.
- 보기 설정 켜기/끄기, 완료로 닫기, 기존 녹화 안내 이동, 영상·사진의 기존 더미 결과 이동 확인.
- 본문 색 대비 계산 5.23:1, 주 액션 글자 대비 4.97:1. 정식 접근성 인증이나 TalkBack 전체 테스트는 미수행.
- 테스트 후 기기 크기 1080×1920, density 420, 기본 글꼴 100%, 크게 보기 OFF로 복원. 홈으로 돌아옴.
- 실제 MediaProjection 녹화, 실제 파일 선택, AI/API 분석, 실기기 테스트는 이번 단계에서 실행하지 않음.

캡처:

- `preview/home-premium-final.png`
- `preview/home-view-settings-final.png`
- `preview/home-premium-343-font130-final.png`
- `preview/home-premium-480.png`
- `preview/home-premium-font150.png`
- `preview/home-settings-large-font150.png`
- `preview/home-premium-large-font150.png`
