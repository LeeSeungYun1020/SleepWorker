# 08 — 히스토리·설정·알림·패키징 (Phase 5)

선행: `07-workflow-editor.md` | 다음: 없음 (릴리스)

## 목표

런 히스토리 열람·워크트리 수동 삭제, 설정 화면, macOS 알림 마무리, dmg 패키징으로 설치형 앱을 완성한다.

## 작업 항목

### 1. 히스토리 화면 (`ui/history/HistoryScreen.kt`)
- [ ] `RunHistory.list(repoPath)` → 런 목록(최신순): runId, 워크플로명, versionId, 상태, 시작/종료, 방문 수
- [ ] 런 선택 → 방문 타임라인(06 컴포넌트 재사용, 읽기 전용) + 로그 파일 로더(`stdout.log` 등 파일에서 지연 로드)
- [ ] 실행 당시 Workflow 스냅샷과 전이 기록으로 우선순위·조건·대상을 표시. 이후 워크플로의 연결/배치를 바꿔도 과거 런의 경로를 재해석하지 않음
- [ ] INTERRUPTED를 "중단된 실행"으로 표시하고 직전 상태·마지막 기록 시각·중단 사유·중단 발견 시각을 제공. 실제 종료 시각을 모르면 별도 표시, 자동 재개 없음
- [ ] [사용한 버전 보기]로 버전 기록 열기, [이 버전으로 복원]은 07의 초안 복원 기능 사용. 런/작업 파일을 되돌리지 않음. 원본 버전 파일이 없으면 런 내부 스냅샷을 표시하고 명시적 가져오기로 새 워크플로 생성 가능
- [ ] 방문 상세: `visit.json` 및 `attempts/<attemptNo>/script.txt`, command.txt, result.json. 자동 재시도별 원문·출력·실패 사유·세션 ID, 수동 재방문의 manualRetryOf 연결 제공. 원래 실패와 사용자의 Skip 판정을 별도 표시
- [ ] 실행 binary 경로/버전·요청 모델/effort·적용 계약 ID 및 제공된 usage 표시. 미관측 종료코드·실제 모델·비용은 알 수 없음으로 남김. 취소/timeout/정리 권한 오류를 같은 exit 1 일반 실패와 구분
- [ ] completion/전이 조건 검사 기록에서 명령·출력·Met/NotMet/Error·실제 종료코드·시간 초과 확인. 세션 ID 복사 및 검증된 계약에 따른 터미널 resume 명령 복사 제공
- [ ] 워크트리 섹션: `git worktree list --porcelain` 결과 표시, 각 항목 [삭제] → 확인 다이얼로그("세션 resume 불가능해짐" 경고) → `WorktreeManager.remove`. 브랜치는 삭제하지 않음(안내)
- [ ] 런 기록 삭제: 디렉토리 삭제(확인 후). 그래프 버전 파일은 유지
- [ ] `.aiflow/runs/`가 `.gitignore`에 없으면 안내 배너(앱이 수정하지 않음)

### 2. 설정 화면 (`ui/settings/SettingsScreen.kt`)
- [ ] CLI 경로: codex, agy 각각 텍스트 필드 + [자동 탐지](`PathDetector`) + [확인](`--version` 실행 결과 표시)
- [ ] PATH와 사용자 지정 경로의 버전을 구분하고 마지막 호환 검사 결과 표시. 앱 번들 CLI는 존재/버전 확인 후 사용자가 선택하는 경로 후보이며 특정 사용자 절대 경로를 배포 기본값으로 넣지 않음
- [ ] Codex 모델 리스트: 편집 가능한 후보 목록(추가/삭제/순서), 목록 포함이 지원 검증을 의미하지 않음. 검증용 저비용 후보 GPT-6 Luna는 별도 계약 확인 후 사용
- [ ] Antigravity 모델: [갱신] → `agy models` TSV 기반 `listModels` → 캐시·binary 경로/버전·갱신 시각 표시. JSON 목록 옵션 호출 금지. 갱신 실패는 진단을 표시하고 과거 캐시를 최신 결과로 표시하지 않음
- [ ] 기본 워크트리 루트(새 워크플로 생성 시 기본값)
- [ ] 알림 on/off, 알림 이벤트 선택(완료 / 실패 / 사용자 확인 필요 / 일시정지됨)
- [ ] 로그 버퍼 상한(기본 20,000줄)
- [ ] 저장 즉시 `settings.json` 반영

### 3. 알림 (`platform/MacNotifier` 마무리)
- [ ] `osascript -e 'display notification "<body>" with title "aiflow" subtitle "<workflow>"'` — 따옴표 이스케이프
- [ ] 알림 클릭 시 앱 활성화는 범위 밖(osascript 한계) — 문서화
- [ ] 알림 발생 지점: COMPLETED / FAILED / AWAITING_USER / PAUSED (설정에 따름)

### 4. 앱 마무리
- [ ] 앱 아이콘(`.icns`) 지정
- [ ] 창 크기·위치 기억(settings.json)
- [ ] 메뉴바: File(새 워크플로/열기/저장/버전 기록), Run(실행/일시정지/재개/중지), Help(계획서 열기)
- [ ] 종료 시 미저장 편집 내용은 초안 저장/버리기/취소. 미완료 런이 있으면 종료 확인 후 `interruptForShutdown()`으로 현재 소유 프로세스 종료·로그 flush·INTERRUPTED 기록 완료 후 앱 종료
- [ ] 미처리 예외 → 다이얼로그 + 로그 파일(`~/Library/Logs/aiflow/app.log`)

### 5. 패키징
- [ ] `compose.desktop.application.nativeDistributions`: `packageName = "aiflow"`, `packageVersion`, `macOS { bundleID = "dev.local.aiflow"; iconFile }`, `targetFormats(Dmg)`
- [ ] `./gradlew :desktopApp:packageDmg` → `desktopApp/build/compose/binaries/main/dmg/aiflow-x.y.z.dmg`
- [ ] 서명·notarization 없음(본인 사용). 첫 실행 시 Gatekeeper 우회 방법을 README에 기재(우클릭 → 열기)
- [ ] dmg 설치 후 **PATH 미상속 상태**에서 설정의 자동 탐지로 CLI를 찾는지 확인(Finder에서 실행)
- [ ] 복수 CLI 경로/구버전 비호환·Auth Unknown·정리 실패 안내는 fixture로 검증. 설치본의 실제 new/resume 확인만 00의 저비용 모델/low effort를 명시해 수행하며 전체 고비용 파이프라인 반복을 기본 검사로 삼지 않음

### 6. 문서
- [ ] `README.md`: 설치, 사전 조건(CLI 로그인), 그래프에서 첫 워크플로 작성(시작 지정·노드 추가·조건 화살표·end/ask 연결), `!` 셸 단계, 전이 우선순위·루프 상한·미매칭 대기, 배치/목록 순서와 실행의 독립성, 세션 ID로 수동 이어가기, 알려진 제한(병렬 실행 없음, 로그인 미지원, 알림 클릭 미지원)
- [ ] `airflow.md`(계획서)와 다르게 구현된 부분 목록
- [ ] 초안 저장과 실행 버전 생성, 이전 버전 복원, 중단 런 열람·새 실행 안내. 그래프 복원과 런 재개/저장소 되돌리기의 차이 명시

## 산출물

- `ui/history/*`, `ui/settings/*`, 메뉴바, 아이콘
- `aiflow-x.y.z.dmg`
- `README.md`

## 완료 기준

- [ ] dmg로 설치한 앱을 Finder에서 실행 → 자동 탐지로 CLI 경로 설정 → 예시 워크플로 완주
- [ ] 히스토리에서 과거 런 로그 열람, 세션 ID 복사 후 터미널에서 resume 성공
- [ ] 워크트리 수동 삭제 동작, 자동 삭제 경로 없음
- [ ] 알림 4종 발생 확인
- [ ] 미완성 그래프 저장·앱 재시작 후 열기, 이전 그래프 버전 복원 후 새 버전 생성과 기존 이력 유지 확인
- [ ] 앱 종료/재시작 후 미완료 런이 중단된 실행으로 기록되며 자동으로 재실행되지 않음

## 주의

- 워크트리 삭제는 이 화면의 명시 동작으로만 가능해야 한다. 엔진·실행 화면 어디에도 삭제 경로를 두지 않는다.
- 설정 파일에 토큰·자격 증명을 저장하지 않는다(로그인은 CLI 책임).
