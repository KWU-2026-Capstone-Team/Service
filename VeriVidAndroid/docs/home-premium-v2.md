# VeriVid 홈 UI/UX 리디자인 · 두 번째 단계

## 범위와 정보 구조

Kotlin / Jetpack Compose / Material 3 유지. 새 라이브러리·폰트·서버를 추가하지 않음.
홈 외 화면, 공통 테마, MediaProjection 서비스, 더미 분석 및 데이터 로직은 변경하지 않음.

1. 브랜드명과 기존 보기 설정 버튼.
2. “이 영상, 진짜일까요?” / 목적을 설명하는 짧은 한 문장.
3. 딥 블루 화면 녹화 카드. 설명과 명확한 CTA “분석 시작하기”.
4. 밝은 중립색 영상·사진 카드. 일반 모드에서는 2열.
5. 분석 기록으로 이동하는 간결한 링크.
6. 시연용 분석임을 알리는 짧은 안내와 기존 3개 탭.

## 디자인 판단

- 메인 카드만 강한 브랜드색 사용. 파일 선택·기록 경로는 더 낮은 강조 수준.
- 일반 모드의 메인 카드는 아이콘·설명을 나란히 배치하여 불필요한 높이를 제거.
- 큰 글씨에서 메인 설명은 세로 배치. 파일 카드는 한 열로 재배치하며 스크롤 허용.
- 카드 16/20dp, CTA 12dp, 좌우 24dp (좁은 화면 16dp), 간격 4/8/12/16/24dp.
- 시스템 SansSerif와 실제 기기의 한국어 글꼴을 재사용. 시스템 글꼴 배율을 축소하지 않음.
- 선 두께 1.65의 기존 벡터 아이콘 사용. 장식용 이미지·강한 그라데이션·그림자 없음.
- 메인 카드 자체는 클릭 불가, CTA만 클릭 가능. 파일 카드는 전체 클릭 가능.
- 설정 48dp, CTA 최소 48dp (크게 보기 56dp), 파일 카드 최소 88/120dp.
- Material 기본 터치 피드백과 설정 시트 동작 활용. 불필요한 애니메이션 추가하지 않음.
- 홈 하단 메뉴에만 새 브랜드색과 무테두리 스타일 적용. 다른 화면 메뉴 소스는 그대로 유지.

토스의 [가치를 먼저 전달하는 원칙](https://toss.tech/article/value-first-cost-later)을
입력 출처의 목적과 액션을 함께 설명하는 방식으로 해석했다. 서비스의 외형을 복제하지 않았다.
Airbnb의 정교한 간격/컴포넌트 리듬, Apple의 절제된 시각 요소는 사용자 제공 방향을 적용했다.
[Compose 접근성 기본값](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)에
맞춰 터치 영역과 상태 의미를 유지한다.

## 기록 데이터 확인

`VeriVidApp.kt`의 `historyEntries`는 날짜와 위험 단계가 고정된 시연용 목록이다.
실제 분석 완료 기록의 저장/조회와 연결되지 않았으므로 홈에 최근 결과·수치를 복사하지 않았다.
기존 `HistoryScreen`으로 이동하는 “분석 기록 보기” 링크만 추가했다.

## 변경 파일

- `app/src/main/java/com/verivid/app/ui/HomeWorkspace.kt`: 홈 전용 토큰, 메인 카드, 파일 카드,
  반응형 재배치, 기록 링크, 홈 전용 하단 메뉴. 기존 설정과 크게 보기 동작 유지.
- `app/src/main/java/com/verivid/app/ui/VeriVidApp.kt`: Home 경로에서만 홈 메뉴 연결,
  기존 History 경로 이동 콜백 추가. 분석 콜백 그대로 유지.
- `docs/home-premium-v2.md`: 이번 단계의 설계 및 검증 기록.

## 기존 구현의 한계

영상/사진 버튼은 실제 파일 선택기가 아닌 기존 더미 분석 콜백에 연결된다.
이번 단계에서 파일 URI 처리나 분석 API를 변경하지 않았다.
화면 녹화 버튼은 기존 녹화 안내 화면으로 이동한다.
실제 파일 선택, MediaProjection 녹화 전체 흐름, AI 추론, 실기기와 TalkBack 검증은 별도 작업이다.

## 검증

- 첫 빌드 성공 (46초). 실제 첫 렌더링에서 기록 링크가 아래로 밀리는 것을 확인했다.
  일반 모드 메인 카드의 아이콘/설명을 가로로 재배치하고 파일 카드 최소 높이를 136→120dp로 줄였다.
- 수정 후 최종 `assembleDebug` 성공 (11초), APK 설치 성공, 명시적 MainActivity 실행 `Status: ok`.
- 411dp / 기본 글꼴: 제목, 메인 CTA, 두 파일 카드, 기록 링크를 첫 화면에서 확인.
- 343dp / 글꼴 130%: 자연스러운 줄바꿈과 한 열 파일 카드. 사진 및 기록 링크는 스크롤로 접근 확인.
- 480dp / 기본 글꼴: 좌우 정렬, 두 열 카드, 기록 링크 확인.
- 411dp / 글꼴 150%: 메인 CTA와 세로 재배치 확인.
- 411dp / 글꼴 150% + 크게 보기: 설정 체크 상태, 완료 버튼, 스크롤 후 사진 및 기록 링크 확인.
- 클릭 검증: 메인 CTA→기존 녹화 안내, 영상/사진→기존 더미 HIGH 결과,
  기록 링크→History, 하단 기록/도움말→기존 화면, 홈 복귀, 설정 크게 보기 ON/OFF.
- 홈 외 화면 코드와 나머지 `app/src/main` 파일의 작업 전후 SHA256 일치.
- 최근 AndroidRuntime 오류 로그에 앱 크래시 없음. 실제 MediaProjection 녹화는 실행하지 않음.
- 테스트 후 기기 1080×1920, density 420, 시스템 글꼴 100%, 크게 보기 OFF로 복원하고 홈을 열어둠.
- 해상도 복원 후 에뮬레이터 캡처가 검게 나오는 현상이 있어 기기를 재부팅했다.
  재부팅 후 기본 크기에서 최종 홈 이미지가 정상 렌더링됨을 다시 확인했다.
- 에뮬레이터 렌더/클릭 검증이며 정식 자동화 회귀 테스트, TalkBack 및 실기기 검증은 미수행.

실제 캡처:

- `preview/home-refined-final.png`
- `preview/home-refined-settings.png`
- `preview/home-refined-large.png`
- `preview/home-refined-343-font130.png`
- `preview/home-refined-343-font130-scrolled.png`
- `preview/home-refined-480.png`
- `preview/home-refined-font150.png`
- `preview/home-refined-settings-large-font150.png`
- `preview/home-refined-large-font150-scrolled.png`
