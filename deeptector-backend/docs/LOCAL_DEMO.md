# GitHub Pages + 내 PC 백엔드

페이지: https://kwu-2026-capstone-team.github.io/Service/

GitHub Pages는 HTML/CSS/JS만 제공합니다. 브라우저는 `http://127.0.0.1:8000`의 **자기 PC**에 접속합니다. 팀원도 각각 백엔드를 켜야 합니다. 외부 서버, 공개 터널, 방화벽 포트 개방은 사용하지 않습니다.

## 처음 한 번

팀 저장소 최신 main을 clone/pull하고 해당 저장소의 `deeptector-backend`로 이동하세요. 다른 체크아웃의 구버전 실행 안내와 섞지 마세요.

```powershell
cd C:\Users\user\Desktop\deeptest\kwu-service
git pull --ff-only
cd deeptector-backend
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
```

실제 저장소 위치가 다르면 cd 경로만 바꾸세요. 기존 MSYS2 `python` 대신 검증한 Windows Python 3.11을 명시합니다. Python이 없다면 python.org에서 3.11을 설치해야 합니다.

## 매번 실행

```powershell
.\.venv\Scripts\python.exe scripts\run_local_demo.py
```

이 명령 하나가 API와 worker를 함께 실행합니다. 터미널을 유지하고 위 Pages 주소에서 **백엔드 연결 → 영상 선택 → 동의 → 업로드하고 분석** 순서로 시험하세요. 완료/실패한 작업은 이력에서 선택 후 삭제할 수 있습니다. Ctrl+C로 두 프로세스를 종료합니다. 작업 도중 종료하면 처리 중 기록이 남을 수 있으며 다음 worker 실행 시 timeout 회수 정책이 적용됩니다.

실행기는 모드를 demo로 고정하고 50MiB 업로드 한도, 정확한 Pages origin 및 로컬 5173 origin만 허용합니다. DB/업로드는 `deeptector-backend/data/local-demo/`에 따로 저장합니다. 이전 수동 테스트 DB와 세션은 옮기지 않습니다. 기본 분석 보관 기간은 24시간이며 worker가 실행 중이어야 만료 정리가 수행됩니다.

## 연결 실패

- 8000 포트를 다른 API가 사용하면 기존 터미널에서 먼저 종료하세요. 실행기는 임의로 다른 프로세스를 죽이지 않습니다.
- Chrome/Edge 등에서 로컬 네트워크 또는 루프백 접근 권한이 표시되면 이 사이트에 허용하세요. 차단했다면 사이트 권한에서 재설정하세요. 브라우저 보안 기능 전체를 끄지 마세요.
- `http://127.0.0.1:8000/health/ready`가 열리는지 확인하세요. 이는 API 확인이며 worker 확인은 아닙니다.
- 기존 수동 실행 명령에는 Pages CORS 설정이 없으므로 새 실행기를 사용하세요. origin은 `/Service/`를 제외한 `https://kwu-2026-capstone-team.github.io`입니다. 같은 origin의 다른 Pages 경로도 브라우저 보안 경계를 공유하므로 팀의 게시 권한을 신뢰할 수 있는 사람으로 제한하세요.
- 회사/학교 브라우저 정책으로 접근이 막히면 로컬 정적 서버로 대신 시험할 수 있습니다. 저장소 루트에서 `.\deeptector-backend\.venv\Scripts\python.exe -m http.server 5173 --bind 127.0.0.1`을 실행하고 `http://127.0.0.1:5173`을 여세요. 이 명령은 실행 중인 API/worker와 별도 터미널에서 실행합니다.

## 세션·모델·게임

- 토큰은 sessionStorage에 저장됩니다. 같은 탭 새로고침은 유지되지만 탭 종료/데이터 삭제 시 소유권을 잃을 수 있습니다. 새 토큰은 이전 데이터를 읽을 수 없습니다. 토큰은 URL이나 Git에 넣지 않습니다.
- demo 점수·얼굴 수는 고정값입니다. 실제 얼굴 검출/분류/XAI가 아닙니다. 실제 모델은 별도 실행 안내를 따르며 이 실행기로 켜지지 않습니다.
- 게임 UI는 실제 API에 연결되어 있지만 기본 게임 영상은 없습니다. `app.game` 등록 절차는 API.md를 참고하세요. 데이터가 없으면 준비되지 않았다는 오류를 표시합니다.
- 원래 11개 화면 시안은 `/prototype/prototype-version.html`에 그대로 있습니다. 실제 연동 페이지와 혼동하지 마세요.

## 검증

```powershell
node --test web/api.test.mjs # 저장소 루트, Node 설치 환경
cd deeptector-backend
.\.venv\Scripts\python.exe -m pytest -q
# 로컬 demo 실행기를 다른 터미널에서 켜 둔 상태, Node 필요
.\.venv\Scripts\python.exe scripts\smoke_local_demo.py
```

2026-10-07 검증: 백엔드 40개, JS 클라이언트 4개 테스트 통과. 합성 MP4로 실제 HTTP 업로드 → demo 완료 → 이력 → 삭제 → 404 및 손상 영상 실패 → 삭제 통과. Pages origin CORS 및 세션 재사용, 게임 데이터 미등록 응답 확인. 내장 검증 브라우저는 API 8000번 포트에서 `ERR_BLOCKED_BY_CLIENT`를 반환하여 해당 브라우저에서 업로드 버튼부터 완료까지의 검증은 불가했습니다. 일반 사용자 브라우저의 로컬 네트워크 권한은 PC 정책에 따라 확인해야 합니다.

공식 참고: [GitHub Pages 제약](https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site), [Chrome Local Network Access](https://developer.chrome.com/blog/local-network-access), [MDN loopback와 mixed content](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Mixed_content).
