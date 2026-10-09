# 05 — 실행 엔진 (Phase 2)

> 2026-10-05 구현 및 검증 완료: [`scripts/phase2-result.md`](scripts/phase2-result.md).
> 아래 체크리스트는 계획 원문이며 구현 API와 검증 근거는 결과 문서를 따른다.

선행: `03-data-model.md`, `04-provider-adapters.md` | 다음: `06-run-screen.md`

## 목표

계획서 §6의 상태 머신을 구현한다: 명시적 `start`와 화살표(`transitions`)를 따라 한 번에 한 단계씩 실행, 스트리밍, completion 확인, 전이 평가(분기·루프·방문 상한), 1회 재시도, 사용자 확인 대기, 일시정지, 워크트리 준비, 런 기록. **실제 CLI 없이 `FakeProcessExecutor`로 전부 테스트**한다. `steps` 배열 순서와 노드 좌표는 실행에 관여하지 않는다.

## 작업 항목

### 1. 런타임 상태 (`engine/RunState.kt`)
- [ ] `enum RunStatus { IDLE, PREFLIGHT, RUNNING, PAUSE_REQUESTED, PAUSED, AWAITING_USER, COMPLETED, FAILED, ABORTED, INTERRUPTED }` — INTERRUPTED는 앱 종료/재시작으로 이어 실행할 수 없는 종료 상태
- [ ] `enum StepStatus { PENDING, PREPARING, EXECUTING, FINALIZING, CHECKING, SUCCEEDED, RETRYING, AWAITING_USER, SKIPPED, FAILED, INTERRUPTED }`
- [ ] `StepVisit(visitNo, stepId, status, startedAt, endedAt?, effectiveMode?, manualRetryOf?, boundSessionId?, attempts: List<AttemptRecord>, result?, transitionTaken?, conditionEvaluations)`
- [ ] `RunState(runId, workflowId, versionId, workflow, status, visits: List<StepVisit>, currentVisit?, sessionIds: Map<sessionName, String>, visitCounts: Map<stepId, Int>, transitionCounts: Map<(stepId, idx), Int>, interruptedAt?, interruptionReason?, previousStatus?, lastUpdatedAt)` — `StateFlow`로 노출. workflow는 실행에 사용한 버전의 불변 스냅샷
- [ ] `AttemptRecord(attemptNo, status, startedAt, endedAt?, sessionId?, exitCode?, termination, providerReport?, completionResult?, failure?, retrySkippedReason?)` — exitCode가 없으면 null, 임의로 0/1 생성 금지
- [ ] 시도 실행 메타데이터에 binary 경로/버전, 계약 ID, 요청 모델/effort와 CLI가 제공한 usage를 보존. 실제 모델/사용량을 알 수 없으면 null로 남기고 가격/비용을 임의 추정하지 않음
- [ ] `LogLine(visitNo, attemptNo?, phase, stream, text, timestamp)` — `SharedFlow`(버퍼 큼) + 런 기록 파일로 동시 기록

### 2. 워크트리 준비 (`git/WorktreeManager.kt`)
- [ ] `resolvePath(workflow, Workspace): String` — local → repoPath, worktree → `worktreeRoot/name` (repoPath 기준 상대 경로 해석)
- [ ] `ensure(workflow, WorktreeDef)`: `git worktree list --porcelain`으로 존재 확인 → 없으면 `git worktree add <path> -b <branch> <base>` (브랜치가 이미 있으면 `-b` 없이)
- [ ] 기존 경로가 다른 저장소/브랜치의 worktree이면 PREPARATION 실패. 프리플라이트 이후에도 ensure에서 일치 여부 재확인
- [ ] `remove(path)`: `git worktree remove <path>` (히스토리 화면에서만 호출)
- [ ] `isDirty(repoPath)`: `git status --porcelain` 비어 있지 않으면 true
- [ ] 모두 `ProcessExecutor` 경유, `FakeProcessExecutor`로 테스트

### 3. 단계 실행 (`engine/StepRunner.kt`)
- [ ] `suspend fun run(step, ctx, visit, attemptNo): StepResult` — 종료/취소 판정까지 한 시도 단위로 관리
  1. PREPARING: 방문 시작 시 유효 모드/세션 결합 결정(§6) → workspace 결정 → worktree ensure → shell 여러 줄이면 임시 파일 생성. 준비 실패는 PREPARATION/SESSION_MISSING 등으로 기록하고 본문 CLI 실행 안 함
  2. EXECUTING: 시도별 parser 생성 → command/script 원문 기록 → `executor.start` → stdout/stderr 동시 수신·원문 기록·`parser.accept` → SessionStarted 즉시 런/방문에 반영. resume에 전달한 ID도 이 시도의 검증된 결합으로 기록
  3. 프로세스 종료 **및 두 스트림 수신 완료**를 모두 기다림. 종료 후 스트림이 닫히지 않으면 내부 drain 제한(기본 5초) 후 남은 소유 프로세스 정리, OUTPUT_INCOMPLETE로 기록. 수신 I/O 오류도 성공으로 간주하지 않음
  4. FINALIZING: `parser.finalizeOutput(exitCode, termination)`을 정확히 한 번 호출 → finalEvents와 최종 sessionId 반영·기록 → ProviderReport 확정. 04의 단일 인터페이스 사용
  5. CHECKING: NORMAL 종료·exitCode 0·출력 수신 완료·provider 성공(SUCCEEDED 또는 shell NOT_APPLICABLE)일 때만 completion 확인. 실패/프로토콜 오류를 completion 성공으로 덮어쓰지 않음
  6. StepResult 확정: 종료 상태·providerReport·completion 결과·구체적 사유를 보존한 뒤 재시도/전이 결정
- [ ] `StepResult(success, exitCode?, termination, providerReport?, completionResult?, failure?, sessionId?, bodyStarted, finalOutput?)`. 성공은 위의 모든 조건과 completion Met를 만족한 경우뿐
- [ ] `timeoutSec`은 각 시도의 본문 프로세스 시작부터 종료·출력 수신까지 적용. 초과 시 killTreeAndWait → 제한된 출력 정리 → TIMED_OUT 실패. FINALIZING은 수집한 출력에 대한 로컬 파싱이며 별도 프로세스를 실행하지 않음
- [ ] 취소는 별도 제어 결과로 상위에 전달. 정리 후 ABORTED/INTERRUPTED를 기록하며 일반 실패 처리·재시도·전이로 흘려보내지 않음
- [ ] 임시 파일 정리(finally). 로그 쓰기/상태 기록 실패는 추가 명령 시작을 막고 런 FAILED 처리. 종료·정리 실패 원문은 앱 로그에 보존. 소유 프로세스 정리를 확인할 수 없으면 런 FAILED로 종료하고 자동 재시도/후속 명령 실행 금지

- [ ] stdout/stderr는 동시 읽되 parser·세션·상태 적용은 한 이벤트 채널에서 직렬 처리. 두 스트림 완료 후 채널을 비우고 finalizeOutput 호출

### 4. 완료 확인과 조건 명령 (`engine/CompletionChecker.kt`, `CheckRunner.kt`)
- [ ] 공통 결과 `CheckResult = Met | NotMet | Error(reason)`와 명령의 exitCode·stdout/stderr·소요·timeout 여부를 기록
- [ ] completion 없음/ExitCode → Met(본문 성공 요건은 이미 확인). FileExists는 없으면 NotMet, 읽기/접근 오류는 Error. FileContains도 없거나 불일치하면 NotMet, 읽기 오류는 Error
- [ ] FileExists의 `*` glob은 단일 디렉토리 항목 이름 매칭만 지원, 이외 glob 구문은 검증 오류. 일반 경로는 workspace 기준
- [ ] completion Command: `/bin/zsh -lc cmd`, cwd=단계 workspace. 정상 종료 0→Met, 정상 non-zero→NotMet, 시작 실패/timeout/I/O 오류→Error. NotMet/Error 모두 단계 실패이며 사유는 구별하여 재시도 정책 적용
- [ ] 전이 Command: 0→Met, 1→NotMet, 2 이상/시작 실패/timeout/I/O 오류→Error. 검사 명령은 false를 1, 검사 자체 오류를 2 이상으로 반환하도록 작성. Error는 Ask로 정지하며 다음 조건/otherwise로 넘어가지 않음
- [ ] `step.checkTimeoutSec`(기본 300초)을 **각 완료 확인/전이 조건 명령**에 적용. 본문 timeout과 별개이며 UI에서 agent/shell 공통 편집 가능
- [ ] 준비용 Git 명령·프리플라이트도 공통 ManagedProcessRunner의 취소 관리 대상. 제어 명령 제한은 기본 30초, 업무용 긴 검사는 checkTimeoutSec으로 지정
- [ ] 모든 프로세스를 런의 활성 프로세스 레지스트리에 등록하고 종료·스트림 정리 후 해제. 본문뿐 아니라 검사 명령·Git·프리플라이트도 강제 중지 시 종료·대기

### 5. 전이 평가 (`engine/TransitionEvaluator.kt`)
- [ ] 입력: step, 최종 StepResult, workspace, 방문의 조건 평가 기록, 카운터 → `Decision(NextStep(id, resetSession, transitionIndex) | End | Ask(reason))`
- [ ] `step.transitions` 순서대로 첫 Met만 선택. Success/Failure는 최종 success, Otherwise는 항상 Met. FileExists/FileContains/Command는 결과 성공 여부와 무관하게 평가
- [ ] 조건 Error→Ask("condition evaluation error", 상세), 미매칭→Ask("no transition matched"), 명시적 ask→Ask("explicit ask"). 빈 전이도 Ask(버전 생성/실행 진입에서는 차단)
- [ ] 같은 방문에서 평가한 외부 조건 결과(Met/NotMet/Error)는 인덱스별로 보존. 일시정지 재개는 이미 선택한 Decision 사용. Skip은 success/failure만 바꿔 순서대로 평가하되, 이미 평가한 외부 조건을 재실행하지 않고 처음 도달한 외부 조건만 한 번 실행. 오류 캐시는 새 방문에서만 초기화
- [ ] 선택 전이의 누적 횟수가 maxVisits 이상이면 따라가기 전 Ask. 이후 전이로 우회하지 않음. 실제로 연결을 따라갈 때 한 번만 증가(명시적 end/ask 포함), 미매칭·오류·상한 대기는 증가 없음. 동일 방문에서 이미 취한 ask 전이를 Skip으로 다시 선택해도 대기 유지이며 중복 계수하지 않음
- [ ] 다음 단계 방문 전 전체 방문 수가 maxSteps 이상이면 Ask. End는 추가 방문 없이 완료. 자동 재시도는 방문을 늘리지 않고 수동 Retry는 새 방문을 소모
- [ ] resetSession은 실제 대상 방문 진입 시 그 세션만 비우고 유효 모드를 NEW로 설정. 대기/미선택 전이에서는 세션을 지우지 않음

### 6. 세션·재시도 (`engine/RetryPolicy.kt`)
- [ ] 방문 시작 모드: 기본은 step.session.mode, 선택 전이의 resetSession이면 NEW. NEW는 해당 세션의 이전 활성 ID를 비우고 시작하므로 루프 재진입 시 새 세션. RESUME은 기존 활성 ID 필수이며 없으면 SESSION_MISSING, 자동 NEW 전환 없음
- [ ] 방문에 결합한 ID와 모드를 유지. NEW의 ID는 이번 방문 출력에서만 채택하고 다른 방문의 오래된 ID를 재사용하지 않음. RESUME의 provider 거부(SESSION_INVALID)는 해당 활성 ID를 제거
- [ ] agy 다른 cwd resume은 원래 workspace에서 실행된다는 실측을 반영해 세션 ID를 provider·정규화된 workspace와 함께 결합. 호출 cwd를 바꾸는 방법으로 세션을 이동시키지 않으며 불일치는 실행 전에 차단
- [ ] 자동 재시도는 같은 방문의 attempt 2로 최대 한 번. 본문이 시작된 실패이고 AUTH/CONFIG/SESSION_MISSING/SESSION_INVALID가 아니며 취소되지 않았을 때만 대상
- [ ] agent 자동 재시도: 방문에 확보/검증된 ID가 있으면 해당 ID로 resume + `retryScript ?: 기본 문구`. ID가 없으면 `retrySkippedReason = "session id unavailable"` 기록 후 최종 실패 전이 평가. 새 세션을 몰래 생성하지 않음. 자동 retry에서는 방문 시작 시의 NEW 초기화를 반복하지 않음
- [ ] shell 자동 재시도: 동일 script rerun. completion NotMet/Error 및 본문 timeout도 위 대상 조건을 만족하면 재시도. 준비 실패나 조건 전이 평가 오류는 본문 자동 재실행하지 않음
- [ ] 기본 retryScript는 원인/종료코드/미충족 조건을 기재한 재시도 안내만 생성하며 단계 간 출력 주입 없음
- [ ] 수동 Retry는 새 visitNo·manualRetryOf를 생성하고 attempt 1부터 원래 step.script로 시작. 직전 방문의 유효 모드를 유지(NEW면 새 세션, RESUME이면 해당 방문에 결합한 유효 ID 사용). resetSession으로 NEW가 된 방문의 수동 Retry도 NEW. RESUME 수동 Retry의 결합 ID가 활성 세션 맵에서도 동일하게 유효해야 하며 제거/거부된 ID를 되살리지 않음. 자동 재시도 자격은 다시 검사
- [ ] 수동 Retry도 maxSteps 검사, 카운터 리셋 없음. 성공/실패로 확정된 이전 시도·방문 기록은 수정하지 않고 사용자 제어 결과를 별도로 기록
- [ ] 재시도 소진/불가 후 최종 실패 결과로 동일한 전이 목록 평가. 조건 오류에 대한 수동 Retry는 검사만 재실행하는 기능이 아니라 **단계 전체 새 방문**임을 UI에 표시

### 7. 오케스트레이터 (`engine/RunOrchestrator.kt`)
- [ ] `start(version: WorkflowVersion, settings)`: 저장된 버전을 로드 → `WorkflowValidator` ERROR 차단 → 실행 정의의 불변 스냅샷과 id 조회 맵 생성 → runId 생성(`yyyyMMdd-HHmmss-xxxx`), workflowId/versionId를 포함한 PREFLIGHT 초기 기록 → 프리플라이트 성공 시 RUNNING 기록 후 `workflow.start`부터 루프(실패는 FAILED 기록). 초안을 직접 실행하지 않으며 실행 중 편집·버전 복원은 현재 런에 영향 없음
- [ ] 루프: 단계 실행 → 자격에 따른 자동 재시도 → 최종 결과 전이 평가 → Decision 처리. 중지/종료 요청 우선, End는 COMPLETED, Ask는 AWAITING_USER, NextStep일 때만 pauseRequested면 PAUSED. terminal/Ask 진입 시 pauseRequested는 소모·해제
- [ ] RUNNING/PAUSE_REQUESTED/PAUSED/AWAITING_USER에서 버튼·이벤트는 단일 제어 루프에서 직렬 처리. 한 번 확정된 terminal 상태를 늦은 이벤트로 변경하지 않고 취소 후 새 프로세스 실행을 차단
- [ ] `pause()`: 플래그만 세움(현재 단계는 계속)
- [ ] `resume()`: PAUSED → RUNNING, 저장된 NextStep Decision을 실행(전이 명령 재평가 없음)
- [ ] `abort()`: 취소 플래그 확정 → 런에 등록된 본문/검사/준비 프로세스 모두 killTreeAndWait → 출력 정리 → ABORTED. 자동 재시도·다음 단계 없음
- [ ] `interruptForShutdown()`: 추가 단계 진입 금지 → 런에 등록된 모든 소유 프로세스 종료·출력 정리·로그 flush → 미완료 런을 INTERRUPTED로 기록. 사용자의 [강제 중지] ABORTED와 구별
- [ ] Ask 처리: 상태 AWAITING_USER, `UserDecision` 채널 대기 — `Retry`(§6의 새 방문, maxSteps 적용) / `Skip`(원래 StepResult 보존, 사용자 성공 간주 값을 별도 기록하고 §5의 캐시 규칙으로 재평가) / `Abort`. Skip도 매칭 없음·ask 대상·상한 초과이면 대기 유지. 목록의 다음 단계로 이동하거나 상한을 우회하지 않음
- [ ] 프리플라이트는 06에서 추가할 `Preflight` 인터페이스 호출 지점만 둔다(여기서는 no-op)

### 8. 런 기록 (`storage/RunRecorder.kt`)
- [ ] 디렉토리 `.aiflow/runs/<runId>/`
  - `run.json` (workflowId/versionId와 실행 당시 Workflow 불변 스냅샷 포함 RunState). 프로세스 시작 전 초기 기록, 각 상태 변경·세션 ID 수신·단계 종료 시 갱신. `transitionTaken`에 출발 단계 id·전이 인덱스·조건·대상을 기록해 당시 우선순위와 선택 이유 재현
  - `visits/<visitNo>-<stepId>/visit.json`: 유효 모드·세션 결합·manualRetryOf·최종 방문 결과·선택 전이·사용자 제어 이력
  - `visits/<visitNo>-<stepId>/attempts/<attemptNo>/`: stdout.log, stderr.log, events.jsonl, command.txt, script.txt(실제 전달 원문), result.json. 최초/자동 재시도 분리, 본문 미실행이면 원인과 bodyStarted=false 기록
  - `attempts/<attemptNo>/checks/completion/`: completion 명령/출력/CheckResult. `visits/<visitNo>-<stepId>/checks/transitions/<index>/`: 외부 조건 명령/출력/CheckResult. 인덱스는 0 기반 저장·UI는 1 기반 표시
  - `preflight/`와 `visits/<visitNo>-<stepId>/attempts/<attemptNo>/preparing/`: 프리플라이트/준비 보조 명령의 순번별 command·stdout/stderr·결과
- [ ] 방문/시도 시작부터 기록하고 확정된 result 파일은 덮어쓰지 않음. 중단된 활성 시도만 INTERRUPTED로 마무리. 늦은 상태 기록이 terminal 결과를 덮어쓰지 않도록 쓰기 순서를 직렬화
- [ ] `.aiflow/runs/`를 `.gitignore`에 추가 권장(앱은 추가하지 않고 UI에서 안내만)
- [ ] `RunHistory.list(repoPath)`: run.json 목록 로드
- [ ] run.json은 임시 파일 작성 후 원자적 교체. 초기 기록에 실패하면 프로세스를 시작하지 않음. 런 기록 자체가 그래프 스냅샷을 포함하므로 원본 버전이 없어도 당시 실행 내용 열람 가능

### 8a. 재시작 후 중단 기록 (`storage/RunRecovery.kt`)
- [ ] 앱 시작 후 저장소를 열 때 해당 저장소에 대한 단일 앱 소유권 잠금을 확보한 뒤 기존 run.json 검사. 다른 살아 있는 앱의 런을 중단으로 바꾸지 않음. 잠금은 플랫폼 인터페이스로 주입
- [ ] 이전 앱의 PREFLIGHT/RUNNING/PAUSE_REQUESTED/PAUSED/AWAITING_USER 런을 INTERRUPTED로 기록. COMPLETED/FAILED/ABORTED/INTERRUPTED는 변경하지 않음. IDLE은 실행 전 UI 상태이며 런 파일로 저장하지 않음
- [ ] previousStatus, 마지막 저장 상태·시각, interruptionReason(app_shutdown / previous_app_session_ended), interruptedAt 기록. 비정상 종료는 실제 종료 시각을 알 수 없으므로 interruptedAt을 발견 시각으로 표시하고 endedAt/exitCode를 추정하지 않음
- [ ] 미완료 방문만 INTERRUPTED로 표시, 이미 확정된 성공/실패·방문 순서·세션 ID·전이 결과·로그는 유지. 로그가 일부만 기록되었을 수 있음을 상세에 표시
- [ ] 재시작으로 단계 실행·자동 재시도·세션 resume·다음 전이 평가를 호출하지 않음. 반복 스캔해도 중단 정보가 바뀌지 않는 멱등 처리
- [ ] 중단 런에는 재개 기능 없음. 사용자가 버전을 선택해 새 런을 시작하면 새 runId·빈 세션 맵·초기 카운터로 start부터 실행하며, 기존 런은 기록으로 유지
- [ ] INTERRUPTED는 러너의 실행 중단 기록이며 외부 CLI 프로세스가 이미 종료됐다는 보장은 아님. 비정상 종료 후 외부 프로세스 상태는 확인되지 않은 상태로 표시하고 자동 재접속·명령 재실행은 하지 않음

### 9. 테스트 (`commonTest`, FakeProcessExecutor + FakeFileSystem)
- [ ] 명시적 연결을 가진 선형 3단계 성공 → COMPLETED, 방문 3회, 마지막은 `success → end`
- [ ] 동일 그래프의 `steps` 배열을 섞고 좌표를 이동·제거해도 시작 단계·방문 순서·전이 선택·최종 결과 동일
- [ ] 여러 조건이 동시에 참이면 명시적 전이 순서의 첫 항목만 선택. 전이 우선순위 변경만 선택 결과를 바꿈
- [ ] `new` → `resume` 세션 ID 전달 (Fake가 thread.started JSONL 방출)
- [ ] completion `FileExists` 미충족 → 재시도(resume + retryScript) → 성공
- [ ] 재시도 실패 + `failure → fix` 전이 → fix로 이동
- [ ] 재시도 실패 + `success → review`만 있음 → AWAITING_USER → Skip → 명시된 review로 이동
- [ ] 전이 미매칭/명시적 ask/상한 초과 → Skip 후에도 해당 조건이면 AWAITING_USER 유지
- [ ] 전이 없는 단계는 검증에서 차단되고, 평가기 직접 호출 시 Ask. 배열 마지막이라는 이유로 완료되지 않음
- [ ] 루프 `verify ↔ fix` maxVisits 초과 → AWAITING_USER
- [ ] review의 첫 줄 정확 일치·검토 SHA 확인 Command 분기, 본문에 APPROVED가 있어도 첫 줄 CHANGES_REQUESTED면 fix
- [ ] `pause()` 호출 후 현재 단계는 완료되고 다음 단계 미진입, `resume()`으로 재개
- [ ] shell 단계 timeout → 본문/출력 정리 완료 → Failed → rerun 1회
- [ ] `resetSession` 전이 후 세션이 new로 생성됨
- [ ] 워크트리 없음 → `git worktree add` 커맨드가 Fake에 기록됨
- [ ] RunRecorder가 파일을 모두 생성
- [ ] 실행 버전의 이후 편집·복원과 무관하게 run.json의 versionId와 스냅샷 유지
- [ ] 저장된 비종료 상태로 재시작 → INTERRUPTED·직전 상태·발견 시각 기록, 프로세스 시작/resume 호출 0회. 반복 처리 시 기록 동일
- [ ] 정상 종료 상태는 복구 처리로 변경되지 않음. 앱 종료 시 미완료 런만 INTERRUPTED, 사용자 중지는 ABORTED
- [ ] 중단 기록 확인 후 새 런 실행은 새 runId·초기 세션/카운터 사용. 초기 run.json 쓰기 실패 시 프로세스 시작 0회

- [ ] 종료코드 0 + provider FAILED/PROTOCOL_ERROR→실패, 마지막 출력의 ID/이벤트 반영 후 판정, stderr 일반 경고는 최종 실패가 아님
- [ ] new에서 이전 ID 제거 후 ID 없이 실패→자동 resume/new 호출 0회·실패 전이, ID 확보 후 실패→같은 ID 자동 resume
- [ ] resume ID 누락/거부·준비 실패는 자동 NEW/rerun 안 함. resetSession은 대상 세션만 초기화, 대기 상태에서는 초기화 안 함
- [ ] 자동 retry: 방문 1회·시도 2회. 수동 Retry: 방문 2회·manualRetryOf·원본 script, maxSteps 경계에서 추가 실행 차단
- [ ] completion 명령 timeout→Error 상세·정리·재시도 정책 적용, 전이 명령 timeout/exit 2→Ask·후속 otherwise 실행 0회
- [ ] 본문/검사/준비 중 abort→모든 등록 프로세스 종료, 이후 retry/next 시작 0회. 종료 후 파이프 미종결은 bounded drain 실패
- [ ] agy `context canceled` + exit 1에서 runner의 CANCELLED/TIMED_OUT을 보존. 사용자가 취소한 실행은 retry 0회, timeout은 기존 자격·ID 확인 후 판단. 오류 문자열/종료코드만으로 취소나 재시도 자격을 추정하지 않음
- [ ] kill 권한 거부/cleanup timeout→원문 로그와 관측된 exitCode(null 포함), 정리 오류 저장→FAILED·후속 실행 0회. 정리 예외 때문에 result가 누락되거나 무제한 wait에 빠지지 않음
- [ ] 비용 절감을 위해 이 단계의 상태 머신·취소·권한 오류·SHA 분기는 fake/로컬 프로세스로 검증. 실모델 호출은 06의 제한된 통합 검증으로 분리
- [ ] pause + End→COMPLETED, pause + Ask→AWAITING_USER, pause + NextStep→PAUSED. resume/Skip 시 이미 평가한 외부 조건 실행 횟수 증가 없음
- [ ] 최초/자동 재시도/수동 재방문의 script·출력·결과 모두 별도 파일 보존, Skip 후 원래 실패 결과 유지

## 산출물

- `engine/*`, `git/WorktreeManager.kt`, `storage/RunRecorder.kt`, `storage/RunHistory.kt`, `storage/RunRecovery.kt`
- 상태 머신 테스트 일체

## 완료 기준

- [ ] 위 테스트 전부 통과
- [ ] 엔진 코드에 플랫폼 의존 import 없음
- [ ] 일시정지가 "다음 단계 진입 직전"에서만 멈춤을 테스트로 증명
- [ ] 목록 순서·노드 좌표 변경이 실행 경로에 영향을 주지 않음을 테스트로 증명

## 주의

- 단계 간 출력 주입 없음. `finalOutput`은 기록·표시용이다.
- 세션 ID는 `SessionStarted` 이벤트를 받는 즉시 상태에 반영해야 한다(프로세스가 중간에 죽어도 resume 가능하도록).
- `Skip`은 "success로 간주"일 뿐 completion을 다시 평가하지 않는다.
