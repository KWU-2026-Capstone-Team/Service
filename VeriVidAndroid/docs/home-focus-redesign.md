# VeriVid Home · Focus & Input

이번 요청의 원형 액션 중심 콘셉트가 현재 홈 설계 기준이다.
이전 홈의 디자인 가이드·와이어프레임은 적용하지 않는다. 다른 홈 문서는 과거 작업 이력이다.

## 정보 구조와 동선

기능 소개를 위에서 아래로 읽는 화면 대신, 하나의 중앙 입력 그룹을 사용한다.

- 최소 헤더: VeriVid / 기존 보기 설정.
- 작은 목적 문구: 영상·이미지 조작 신호 확인.
- 중심: 104dp 원형 버튼 → 기존 CaptureGuide → 기존 권한/녹화 준비 흐름.
- 버튼 아래: 화면 녹화 시작 / 짧은 설명.
- 보조 입력: 동영상 선택 / 이미지 선택을 한 행에 나란히 배치.
- 보조 기록: 기존 History 경로 링크만 표시.
- 기존 홈 / 분석 기록 / 도움말 탭 유지.

화면 녹화는 버튼을 누르는 즉시 시작되지 않는다. 기존 안내에서 사용자가 녹화를 준비한다.
홈에는 REC, 실시간 탐지, 분석 중 상태나 가짜 스캔 진행 효과가 없다.
파일 두 버튼은 실제 파일 선택기가 아닌 기존 더미 분석 콜백을 그대로 사용한다.

## 시각 언어

- 카드·리스트·큰 헤드라인 구성 제거. 큰 단색 패널, 외곽 링, 그림자, 그라데이션 없음.
- 순수한 원형 브랜드 컬러 #515BD0에 화이트 프레임 + V 심볼.
- 심볼은 코드로 작성한 독자적인 벡터이며 Lens/Shazam 로고를 복제하지 않았다.
- 화이트 바탕, 본문 #242B3A, 보조 #626D80.
- 헤더 18sp, 기능명 17sp, 입력 버튼 14sp, 설명 13sp. 크게 보기·시스템 글꼴 배율 존중.
- 원형 버튼: 기본 104dp, 크게 보기 112dp. 내부 심볼 44dp.
- 보조 버튼: 최소 52dp, 크게 보기 64dp. 아이콘 20dp, 투명해 보이는 화이트 배경 + 기본 터치 피드백.
- 헤더 최소 56dp, 설정 48dp. 기존 설정 바텀시트 동작 유지.
- 메인 그룹과 기록 경로 사이 32dp. 전체 그룹을 중앙에 배치하되, 높이를 강제하지 않는다.
- 내용이 많거나 viewport가 짧으면 스크롤. 행 높이 고정·텍스트 줄 수 제한·글꼴 축소 없음.
- 누른 상태에서만 크기 96%와 색상 변화, 120ms 전환. 반복 스캔/호흡 효과는 없음.
- 원형 액션은 명시적 접근성 이름 '화면 녹화 시작'과 Button 역할을 갖는다.

## 참고

[Google Lens](https://search.google/ways-to-search/lens/)의 카메라/저장 이미지/스크린샷처럼
입력 출처를 빠르게 고르는 원리를 영상·이미지·화면 녹화에 맞게 재해석했다.
[Shazam Android 안내](https://support.apple.com/en-ie/118557)의 하나의 버튼으로 시작하는
사용 흐름을 참고하되 녹화나 탐지 기능을 자동 실행하지 않는다.
[Compose InteractionSource](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions)를
사용하여 기존 플랫폼 클릭 동작을 유지한 채 누르는 반응만 추가했다.

## 변경 범위

- 수정: `app/src/main/java/com/verivid/app/ui/HomeWorkspace.kt` 전체 홈 전용 표현 재작성.
  `HomeHeader`, `HomeWorkspace`, `HomeNavigation` 진입점과 콜백 계약은 동일하다.
- 추가: `docs/home-focus-redesign.md`.
- 변경 금지 범위: `VeriVidApp.kt`, MainActivity, CaptureOverlayService, Theme, 공유 아이콘 원본,
  다른 화면 및 기존 분석/데이터 로직.
- `historyEntries`가 고정 시연 데이터이므로 최근 결과나 썸네일을 임의로 추가하지 않았다.
- 새 의존성·API·백엔드·데이터 저장·설정 기능을 추가하지 않았다.

## 검증

- 최종 `assembleDebug` 성공 (7초). APK 설치 성공, MainActivity 실행 `Status: ok`.
- 첫 렌더링에서 목적 문구와 기록 경로가 중앙 액션에서 너무 떨어져 있었음을 확인했다.
  입력 요소를 하나의 중앙 그룹으로 묶고 기록 경로 간격을 32dp로 정리한 후 다시 빌드·캡처했다.
- 411dp / 기본 글꼴: 원형 버튼, 두 파일 버튼, 기록 경로가 첫 화면에 표시됨.
- 360dp / 약 393dp / 432dp: 정상 정렬, 세 입력 및 기록 경로 표시, 수평 잘림 없음.
- 글꼴 150%: 텍스트 확대 후에도 원형 버튼과 가로 입력 버튼 유지.
- 411dp + 글꼴 150% + 크게 보기: 설정 체크 상태/완료 및 모든 입력·기록 경로 확인.
- 360dp + 글꼴 150% + 크게 보기: 설명/파일 이름이 자연스럽게 줄바꿈,
  스크롤 후 이미지 선택과 기록 링크 접근 확인.
- 원형 버튼을 누르고 있는 실제 프레임 캡처: 일반 273px에서 약 262px로 줄어든 모습 확인.
  손을 뗀 후 기존 CaptureGuide로 이동함을 확인했다. 반복 애니메이션이나 가짜 탐지 상태는 없음.
- 실제 클릭: 원형→녹화 안내, 동영상/이미지→기존 HIGH 더미 결과,
  기록 링크→기존 날짜별 기록, 하단 도움말→기존 도움말, 홈 복귀, 크게 보기 ON/OFF.
- 홈 파일을 제외한 모든 `app/src/main` 파일의 작업 전후 SHA256 일치.
- 최근 AndroidRuntime 오류 로그에 VeriVid 크래시 없음.
- 테스트 후 기기 density 420, 글꼴 100%, 크게 보기 OFF, 홈 화면으로 복원했다.
- 실제 권한 허용/녹화 전체, 파일 선택기, API 추론 및 실기기/TalkBack은 검증하지 않았다.

캡처:

- `preview/home-focus-final.png`
- `preview/home-focus-pressed.png`
- `preview/home-focus-360.png`
- `preview/home-focus-393.png`
- `preview/home-focus-432.png`
- `preview/home-focus-font150.png`
- `preview/home-focus-settings-large-font150.png`
- `preview/home-focus-large-font150.png`
- `preview/home-focus-360-large-font150.png`
- `preview/home-focus-360-large-font150-scrolled.png`

## 기존 한계

실제 파일 선택기/최근 기록 저장/AI 분석은 아직 연결되지 않았다.
실제 MediaProjection 녹화 전체 실행, 실기기, TalkBack 및 정식 회귀 자동화는 이번 범위 밖이다.
