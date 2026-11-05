# VeriVid 선택 화면 + 판별 게임 — 2026-11-05

## 적용한 흐름

앱 실행 → 영상 분석 / 판별 게임.

- 영상 분석 → 기존 홈 → 화면 녹화 또는 저장된 영상 → 전송 동의 → 실제 모델 분석 → 행동 신호등.
- 판별 게임 → 난이도·문제 수 → 영상 재생 → REAL/FAKE·확신도 → 서버 채점/해설/AI 비교 → 다음 라운드 → 결과 리포트.

게임 UI는 Kotlin/Compose, 재생은 Android VideoView이다. WebView, 외부 웹사이트 링크나 별도의 Next.js 서버를 실행하지 않는다. 기존 팀 FastAPI/worker를 그대로 사용한다. 원본 모델·게임·서비스 코드는 수정하지 않았다.

## 확인한 저장소와 규칙

- [deepGame/main](https://github.com/KWU-2026-Capstone-Team/deepGame/tree/main), commit `85d18621a8d82e7299036aa8725b150932b1a617`
- [Service/main](https://github.com/KWU-2026-Capstone-Team/Service/tree/main), commit `3a44aa340c96421dccf33dec0ee52914a74e333d`
- deepGame README·PROGRESS·타입·출제/해설 코드, Service INTEGRATION_CONTRACT·게임 API/채점/마이그레이션·import/evaluate 코드와 기존 게임 프론트 흐름을 확인했다. 대상 브랜치에 별도 AGENTS.md는 없다.
- 정답과 채점은 서버에서 처리, 같은 세션 공유, 답변 전 정답/AI 점수 비공개, 미평가 AI 값 조작 금지, 재생 실패는 skip으로 별도 처리한다.
- 팀 영상의 공개 재배포 권한은 검증되지 않았다. 로컬 검토 전용으로 사용하며 앱 assets나 공개 배포물에 영상·정답 목록을 넣지 않는다.

## 실제 영상과 AI 모델

원본은 `integrations/deepGame-reference/`에 clone했다. GitHub에는 기본 MP4 **24개(REAL 12, FAKE 12)**가 포함되어 있다. README에서 언급한 대규모 추가 Datasets는 Git에서 제외되어 있어 이번에 수집하지 않았다.

기존 `app.import_deepgame` CLI로 같은 로컬 서버 DB에 24개를 등록했다. 원본 영상 파일은 변경하지 않았다. 이 작업은 팀 서버의 기존 등록 기능을 사용한 것으로 새 백엔드나 DB 구조를 만든 것이 아니다.

게임 저장소의 ViT 분류 스크립트는 난이도 자동 분류용이며, custom detector는 별도 연결 지점이다. 이번 앱의 AI 대결에는 앞서 영상 분석에 연결한 팀 공간·시간 이중 브랜치 모델을 사용한다. 임시 외부 모델을 새로 다운로드하거나 자동으로 재학습하지 않았다.

기존 `app.evaluate_games` CLI로 24개 모두 실제 평가하고 `game_clip_evaluations`에 기록했다. 모델 버전 `dual-branch-d28bda40d902b7d0`. 게임 생성 시 실제 평가값이 스냅샷으로 고정되며, AI 판정이 데이터셋 정답을 대체하지 않는다. 게임 도중에는 무거운 추론을 반복하지 않는다. 평가가 없는 영상의 AI 점수는 `미평가`로 표시한다.

실행 환경은 이전 모델 연결과 같은 Python 3.12 CPU 환경이며 OMP/MKL/OpenBLAS 스레드 2개로 제한했다. 실제 가중치 실행은 확인했지만 CPU 온도·배터리·일반화 성능을 측정한 것은 아니다.

## 로컬 준비와 실행

워크스페이스 루트에서:

```powershell
# 영상/해설 등록. 원본 reference 폴더가 있어야 한다.
.\integrations\Prepare-Game.ps1
# 실제 AI 비교값까지 준비. 기존 평가값은 유지하고 미평가 영상만 실행한다.
.\integrations\Prepare-Game.ps1 -EvaluateAi
# 기존 영상 분석과 같은 서버
.\integrations\Start-ModelServer.ps1
# 종료
.\integrations\Stop-ModelServer.ps1
```

이 PC의 등록/평가는 이미 완료했다. 모델/데이터/DB/토큰은 APK에 포함되지 않으므로 다른 PC에 APK만 복사해서는 작동하지 않는다. 모델을 바꾸고 재평가하려면 기존 평가 CLI의 모델 버전 및 `--force` 동작을 팀과 확인해야 한다.

PC API는 `127.0.0.1:8000`, 에뮬레이터 앱은 `10.0.2.2:8000`. 인터넷에 서버를 공개하지 않았다. 앱 release는 HTTPS 주소가 필요하다.

## 동작 및 예외 처리

- 난이도 전체/쉬움/보통/어려움, 5/10/15문제. 정답 균형 조건은 기존 서버가 결정한다.
- 기본 영상 구성은 쉬움 REAL 3+FAKE 4, 보통 4+4, 어려움 5+4이다. 난이도별 10/15문제는 부족할 수 있다. 문제 수를 몰래 줄이지 않고 서버 409를 안내한다. 전체 난이도는 5/10/15문제 구성이 가능하다.
- 같은 익명 세션/Bearer token을 영상 분석과 공유한다. 공용 API 인스턴스로 세션 동시 생성 경합도 방지한다.
- 영상은 인증 요청으로 내려받고, 앱 비공개 `cache/game-video/round-UUID.mp4`에서 음소거 재생한다(최대 32MiB). 인증 토큰을 외부 플레이어나 다른 호스트에 보내지 않는다.
- 다음 라운드/결과 전환 시 임시 파일을 지우며 프로세스 재시작 시 이전 게임 캐시만 정리한다. 앱 중단 시에도 원본 서버/게임 파일은 삭제하지 않는다.
- 재생 준비가 끝나기 전 답변 금지. 재생 중/종료 후 답변 가능. 다시 보기 1회는 기기 UI 제한이며 강력한 부정행위 방지 정책이나 랭킹 보안은 아니다.
- 응답 대기 중 중복 터치를 막는다. 확인되지 않은 답변은 같은 판정/확신도로만 재시도하도록 기기에 저장한다. 서버가 같은 답변을 멱등 처리한다. 영상 재생 후 만료된 idle 연결을 POST가 재사용하지 않도록 쓰기 전용 연결 풀을 분리했으며 POST 자동 재전송은 계속 금지한다.
- 재생 실패는 재시도 또는 `VIDEO_UNAVAILABLE` skip으로 처리한다. 오답을 꾸며 저장하지 않는다.
- 진행 게임 ID를 저장해 다시 열면 서버의 다음 미응답 문제를 이어한다. 제출 직후 응답을 받지 못한 경우에는 해당 문제의 응답을 먼저 확인한다.
- 결과의 점수/콤보/등급/평균 시간은 서버 반환값이다. 건너뛴 문제와 AI 미평가 문제의 분모를 구분한다. AI 미평가를 0점·오답으로 바꾸지 않는다.
- 게임 해설은 팀 데이터셋 문구를 표시한다. 모델/XAI가 그 단서를 증명했다고 표현하지 않는다.

## 실제 검증

- 빌드 성공, JVM contract test **25개 통과**(영상 분석 12 + 게임 13).
- U_Pixel_2 에뮬레이터에서 새 선택 화면과 네이티브 게임 화면 렌더링 확인.
- 쉬움/5문제로 실제 게임 생성 → 음소거 MP4 재생 → 다시 보기 → REAL/FAKE 답변 → 서버 해설·AI 비교 → 5문제 완료 → 리포트 확인.
- 다시 보기 사용 후 버튼 비활성화 확인.
- 1번 답변 후 프로세스를 종료·재실행하고 `이어서 플레이`로 2번 문제 복원 확인. 앱 업데이트 뒤에도 같은 세션/게임이 유지되었다.
- 서버가 반환한 +100, +110 콤보와 최종 210점/D등급, 사용자 2/5·AI 3/5가 UI에 일치했다. 테스트 답변을 임의로 정해 흐름을 확인한 것이며 사용자 능력/모델 정확도 평가가 아니다.
- 쉬움/15문제의 실제 `GAME_DATASET_NOT_READY` 안내 확인. 자동 축소·가짜 문제 생성 없음.
- 재검증에서 답변 전송 연결 오류를 발견했다. 기기에 보관한 같은 답변으로 재시도하여 해설 복원을 확인했고, 읽기/쓰기 연결 풀을 분리하고 회귀 테스트를 추가했다.
- 최종 APK에서 각 문제를 준비한 뒤 7초 이상 기다려 idle 연결 만료 조건을 반복했고, 2~5번 답변·해설·다음 전환·전체 리포트까지 재검증했다. 두 번째 리포트는 사용자 4/5, AI 4/5를 실제 서버 응답대로 표시했다. 이 역시 연결 시험이며 성능 주장이 아니다.
- 실제 데이터 24개 등록/24개 평가 확인, 게임/서비스/모델 원본 Git 추적 파일 변경 없음.
- 기존 영상 분석 홈·녹화 안내·분석 기록 2개 조회를 다시 확인했다. 녹화 Activity/Service, 기존 분석 홈과 테마는 변경 전 파일과 해시가 일치한다. 실제 휴대폰 전체 녹화 흐름, 네트워크 단절/재생 불가의 모든 실기기 조합 및 큰 글꼴/작은 화면 전체 조합은 별도 검증 대상이다.

기존 모델 연결 때와 같이 한글 경로의 Gradle test worker 문제를 피해 ASCII 빌드 출력 경로를 사용했다.

```powershell
# VeriVidAndroid에서 JDK 17 설정 후
.\gradlew.bat assembleDebug testDebugUnitTest `
  '-PverividBuildRoot=C:/Users/user/AppData/Local/VeriVidBuild/model-integration' `
  '-PverividDebugKeystore=C:/Users/user/.android/debug.keystore' --console=plain
```

## 변경 파일

수정: `ui/VeriVidApp.kt`(첫 진입·뒤로/탭 이동), `ui/VeriIcons.kt`(게임 아이콘), `data/AnalysisController.kt`(공용 연결 사용), `data/DeeptectorApi.kt`(게임 API), `README.md`, `integrations/.gitignore`.

추가: `ui/FeatureHub.kt`, `ui/GameExperience.kt`, `data/GameModels.kt`, `data/GameController.kt`, `data/ServiceConnection.kt`, `GameContractTest.kt`, `integrations/Prepare-Game.ps1`, 이 문서.

녹화 권한/서비스, 모델 가중치/임계값, 원본 서버 채점·DB 구조는 변경하지 않았다. 변경 전 Android 파일은 `game-integration-backup-20261105/`에 보존했다. GitHub push는 하지 않았다.

스크린샷은 `preview/game-hub.png`, `game-setup.png`, `game-play.png`, `game-reveal.png`, `game-report.png`, `game-shortage.png`. 최종 설치 APK 복사본은 `outputs/VeriVid-integrated-debug.apk`.
