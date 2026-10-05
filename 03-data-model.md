# 03 — 데이터 모델 + YAML + 검증 (Phase 1b)

선행: `02-create-project.md` | 다음: `05-engine.md` (04와 병행 가능)

## 목표

계획서 §3의 모델을 `kotlinx.serialization` 데이터 클래스로 구현하고, kaml로 YAML 로드/저장, 초안·불변 그래프 버전 저장소, 그리고 버전 생성·실행 시 수행할 **그래프 검증**(§8)을 구현한다. 검증 ERROR가 있는 초안도 저장할 수 있다.

## 작업 항목

### 1. 데이터 클래스 (`model/`)
- [ ] `Workflow(name, repoPath, baseBranch, worktreeRoot = "../<repo>.worktrees", start: String, maxSteps = 50, worktrees: List<WorktreeDef>, sessions: Map<String, SessionDef>, steps: List<Step>, editor: EditorLayout? = null)` — `start`는 실행 가능한 버전에 필수. 초안의 미지정은 빈 문자열로 저장하고 버전 생성·실행 차단
- [ ] `WorkflowVersion(workflowId: String, versionId: String, createdAt: Instant, restoredFrom: String? = null, workflow: Workflow)` — 버전 파일의 YAML 최상위 구조. workflowId/versionId는 UUID이며 이름 변경과 무관. 그래프·스크립트·배치를 함께 보존
- [ ] `WorkflowDraft(workflowId: String, restoredFrom: String? = null, workflow: Workflow)` — 초안 파일의 YAML 최상위 구조. 복원 출처를 앱 재시작 후에도 유지
- [ ] `EditorLayout(nodes: Map<String, NodePosition>)`, `NodePosition(x: Float, y: Float)` — 단계 id 및 특수 노드 `start`/`end`/`ask`의 캔버스 좌표. 선택적 표시 메타데이터이며 엔진·도달성 검증에서 무시
- [ ] `WorktreeDef(name, branch)`
- [ ] `SessionDef(provider: Provider, workspace: Workspace)` / `enum Provider { CODEX, ANTIGRAVITY }`
- [ ] `sealed Workspace { Local; Worktree(name) }` — YAML에서는 `local` / `worktree:<name>` 문자열. 커스텀 serializer
- [ ] `Step(id, title?, kind: StepKind = AGENT, session: SessionRef?, model?, effort?, workspace?, script, retryScript?, timeoutSec?, checkTimeoutSec: Int = 300, completion: Completion?, transitions: List<Transition> = emptyList())` — timeoutSec은 agent/shell 본문 실행·출력 수신에 적용(미지정 시 본문 제한 없음), checkTimeoutSec은 개별 completion/전이 조건 명령에 적용. 빈 전이는 초안용이며 실행 기본값이 아님
  - `SessionRef(ref, mode: NEW | RESUME)`
  - `enum Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }` — 저장 가능한 표기와 실행 지원을 분리. agy 1.2.16 도움말에는 xhigh/max도 있으나 실제 성공 실측은 low. provider·CLI 버전·모델별 지원 여부는 04 계약과 06 프리플라이트에서 검사
- [ ] `sealed Completion { ExitCode; FileExists(path); Command(cmd) }`
- [ ] `Transition(when: Condition, next: Target, maxVisits: Int?, resetSession: Boolean = false)`
  - `sealed Condition { Success; Failure; Otherwise; FileExists(path); FileContains(path, text); Command(cmd) }`
  - `sealed Target { StepId(id); End; Ask }` — YAML `end` / `ask` / 그 외 문자열
- [ ] `!` 규칙: `Step.effectiveKind` — `kind == SHELL || script.trimStart().startsWith("!")` 이면 SHELL. `Step.shellScript`는 첫 비공백 위치의 `!` 하나만 제거하고 나머지 공백·개행을 보존. `!`가 없으면 원문 그대로 사용
- [ ] `steps` 배열은 저장용 목록이며 실행 순서가 아님. 엔진은 id로 단계를 조회하고 `start`와 명시적 `transitions`만 사용
- [ ] `transitions` 생략은 빈 목록으로 읽되 버전 생성·실행 검증 ERROR. 초안 저장은 허용. 마지막 단계도 `next: end`를 명시. 목록 순서 기반 전이 생성 함수 없음
- [ ] 각 단계의 `transitions` 배열 순서만 분기 평가 우선순위(1부터 표시)를 의미. 노드 좌표·화살표의 기하학적 배치는 우선순위와 무관

### 2. YAML 직렬화 (`storage/WorkflowCodec`)
- [ ] `fun decode(yaml: String): Workflow`, `fun encode(wf: Workflow): String`
- [ ] 계획서 §4 예시 YAML을 `commonTest/resources/feature-dev.yaml`로 저장
- [ ] 라운드트립 테스트: decode → encode → decode 가 동일 객체
- [ ] 알 수 없는 키 → 명확한 에러 메시지(kaml `strictMode`)
- [ ] 좌표가 없는 YAML도 로드 가능. 자동 배치는 편집기에서만 수행하고 좌표의 저장·제거가 실행 정의를 바꾸지 않음
- [ ] 현재는 계획 단계이므로 구형 암묵 전이 호환 모드·자동 변환 없음. `start` 누락은 로드 오류, 전이 누락은 검증 오류로 명시적 연결을 안내
- [ ] `WorkflowDraft`/`WorkflowVersion`의 encode/decode도 제공. §4의 단독 Workflow YAML은 가져오기/내보내기 형식이며 저장소 파일의 래퍼와 구별

### 2a. 초안·버전 저장소 (`storage/WorkflowStore`)
- [ ] 경로: `.aiflow/workflows/<workflowId>/draft.yaml` 및 `versions/<versionId>.yaml`. 파일명에 표시 이름을 사용하지 않아 이름 변경 시에도 이력 유지
- [ ] `saveDraft(draft)`는 그래프 검증 ERROR와 무관하게 직렬화 가능한 편집 상태를 저장. 빈 그래프·빈 start·빈 script·미연결 단계도 다시 열 수 있음
- [ ] `saveVersion(draft)`는 ERROR가 없고 WARNING 확인을 마친 경우에만 새 버전 파일 생성. CLI 로그인·파일시스템의 현재 실행 가능 여부는 실행 전 프리플라이트에서 재검사
- [ ] 이미 저장된 버전은 수정·덮어쓰기하지 않음. 최신 버전과 Workflow 내용이 같고 복원 작업이 아니면 해당 버전을 반환해 중복 파일 생성 방지
- [ ] `listVersions(workflowId)`는 생성 시각과 versionId를 표시. `restoreToDraft(workflowId, versionId)`는 해당 버전을 초안으로 복사하고 `restoredFrom` 기록. 복원 후 버전 저장 시 새 versionId를 부여하며 원본과 이후 버전은 유지. 성공 후 초안의 restoredFrom은 비움
- [ ] 단독 YAML 가져오기는 새 workflowId의 초안으로 저장 → 검증 → 버전 생성. 다른 이름으로 복제는 새 workflowId, 단순 이름 변경은 기존 workflowId 유지
- [ ] 초안은 같은 디렉토리의 임시 파일 작성 후 원자적으로 교체. 버전은 임시 파일 작성 완료 후 기존 파일을 덮어쓰지 않는 방식으로 확정. 실패하면 기존 초안/버전 유지, 임시 파일은 버전 목록에서 제외. 쓰기 작업은 직렬화
- [ ] 런 기록 삭제는 버전 파일을 삭제하지 않음. 워크플로 삭제는 초안·전체 버전 삭제 범위를 보여주고 명시적 확인 후 수행하며 실행 중인 워크플로는 삭제 차단

### 3. 검증기 (`model/WorkflowValidator`)
반환: `List<Issue(severity: ERROR|WARNING, stepId?, transitionIndex?, message)>` — 전이 오류를 해당 화살표/속성에 연결
- [ ] ERROR: 단계가 없거나 `start`가 비어 있거나 실제 단계 id를 참조하지 않음
- [ ] ERROR: `Target.StepId`가 실제 단계 id를 참조하지 않음
- [ ] ERROR: 중복 step id
- [ ] ERROR: step id·세션명·워크트리명이 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`와 불일치하거나 같은 범위에서 중복. step id의 특수 노드 예약어 `start`/`end`/`ask` 사용 금지. 표시 이름/title은 이 제약과 분리
- [ ] ERROR: `maxSteps`·지정된 `maxVisits`·`timeoutSec`·`checkTimeoutSec`이 양의 Int 범위가 아님. 좌표는 유한 수여야 함
- [ ] ERROR: 조건/완료 확인의 path·command·검색 text가 비어 있음, repoPath·baseBranch·worktreeRoot가 비어 있음
- [ ] ERROR: FileExists에 단일 디렉토리 이름의 `*` 이외 glob(`**`, `?`, `[]`, 상위 경로 부분의 wildcard)을 사용. FileContains는 glob 없이 파일 경로 사용
- [ ] ERROR: `transitions`가 없는 단계(빈 목록 포함). 종료는 `next: end`, 명시적 확인 대기는 `next: ask`로 연결
- [ ] ERROR: `otherwise`가 여러 개이거나 마지막 전이가 아님
- [ ] ERROR: `resetSession: true`의 대상이 agent 단계가 아님
- [ ] ERROR: agent 단계의 session이 없거나 `session.ref`가 `sessions`에 없음
- [ ] ERROR: `resume` 단계가 `workspace`를 별도로 지정하면 오류(세션의 워크스페이스를 따름)
- [ ] ERROR: start에서 도달 가능한 resume 진입점에 해당 세션 생성 경로가 전혀 없음. 생성은 앞서 방문한 new 단계 또는 해당 세션의 agent 대상을 향한 resetSession 전이를 포함. 조건을 만족할 수 있다고 가정한 보수적 그래프 분석 사용
- [ ] WARNING: 생성 경로는 있지만 생성 단계를 우회하는 경로도 존재. 세션별 생성 이력 유무를 가진 상태로 start부터 고정점 탐색하여 루프도 처리. resetSession은 대상 진입 직전의 생성 시도로 처리하며 새 세션이 반드시 성공한다고 보장하지 않음
- [ ] ERROR: shell 단계의 workspace가 없거나 `local`/정의된 worktree 중 하나가 아님
- [ ] ERROR: 공백뿐인 `script` 또는 `!` 해석 후 공백만 남는 shell 본문. 검사에는 isBlank를 사용하되 저장/전달 원문을 trim하지 않음
- [ ] ERROR: `Workspace.Worktree(name)`이 `worktrees`에 없음
- [ ] effort는 스키마가 아는 값인지 검사하되 Antigravity라는 이유만으로 XHIGH/MAX를 일괄 금지하지 않음. 저장은 실행 가능성 보장이 아니며 미지원/미검증 조합은 06에서 실행 차단
- [ ] WARNING: 도달 불가 단계
- [ ] WARNING: `otherwise`도 없고 `success`와 `failure` 모두를 포함하지도 않아 매칭 실패 가능한 단계. 조건 미매칭은 런타임 Ask 안전 정지이며 숨은 연결을 생성하지 않음
- [ ] WARNING: `end`에 도달하는 경로가 없음
- [ ] WARNING: shell 스크립트에 위험 패턴(`rm -rf`, `--force`, `push -f`, `reset --hard`) 포함
- [ ] WARNING: 저장소 경로가 존재하지 않음(파일시스템 접근은 주입된 okio FileSystem으로, 테스트에서는 FakeFileSystem)
- [ ] 파일 경로 규칙: workflowId/versionId/runId는 앱 생성 값으로 제한하고, 저장소 경로와 결합한 결과가 의도한 저장 디렉토리 안인지 I/O 경계에서 확인. 초안이 잘못된 step id를 포함해도 id를 파일명으로 사용하지 않음
- [ ] worktreeRoot는 명시적으로 저장소 밖 상대 경로를 허용. 워크트리 이름은 한 경로 요소로 제한하고, 최종 worktree 경로가 해석된 root 안에 있는지 확인. branch 유효성은 프리플라이트에서 Git으로 검증
- [ ] 정적 세션 검증은 생성 시도의 경로만 확인하며 CLI 성공/세션 ID 획득을 보장하지 않음. 런타임에서는 항상 실제 세션 ID 확인. ERROR/WARNING은 초안 저장을 막지 않음

### 4. 테스트 (`commonTest`)
- [ ] 예시 YAML 로드 → 이슈 0건(ERROR 기준)
- [ ] 각 ERROR 규칙마다 실패 케이스 1건
- [ ] 세션 생성 경로 없음→ERROR, 생성 우회→WARNING, resetSession으로 직접 진입→생성 경로 인정, 루프 분석 종료. new 실패 뒤 resume의 ID 누락 차단은 엔진 테스트
- [ ] 참조가 존재하는 정상 사례는 ERROR 아님. 0/음수/Int 범위 초과·예약어·경로 구분자/상위 경로 요소·NaN/Infinity 실패, 양의 경계값 정상
- [ ] `!` 자동 해석: `kind` 미지정 + `!git log` → SHELL, 선두 공백 허용
- [ ] `start` 누락·전이 누락/빈 목록·종료 연결 누락 검증 테스트. 나가는 전이가 전혀 없으면 ERROR, 일부 결과만 처리하면 WARNING
- [ ] `steps` 배열 재정렬 및 좌표 변경·제거 전후에 도달성/실행 관련 검증 결과가 동일
- [ ] 좌표 포함/미포함 YAML 라운드트립, `transitions` 순서 보존
- [ ] ERROR가 있는 초안 저장·다시 열기 성공, 동일 초안의 버전 생성·실행은 차단
- [ ] 유효한 그래프를 변경·저장하면 서로 다른 불변 버전 파일 생성. 이름 변경 후에도 같은 이력 유지
- [ ] 이전 버전 복원 → 새 버전 저장 시 그래프·스크립트·배치와 restoredFrom 보존, 원본/후속 버전 불변. 버전 파일 저장 실패 시 기존 기록 보존

## 산출물

- `model/*.kt`, `storage/WorkflowCodec.kt`, `storage/WorkflowStore.kt`, `model/WorkflowValidator.kt`
- `commonTest/resources/feature-dev.yaml` + 테스트

## 완료 기준

- [ ] 예시 YAML 라운드트립 테스트 통과
- [ ] 검증 규칙 전부에 대한 테스트 통과
- [ ] `Workspace`, `Target`, `Condition`, `Completion` 의 YAML 표현이 계획서 §4 예시와 동일
- [ ] 시작·다음 단계·종료를 목록 순서 또는 좌표에서 추론하는 코드가 없음
- [ ] 초안 저장과 실행 가능한 버전 생성을 구분하고, 이전 버전 복원이 기존 기록을 덮어쓰지 않음

## 주의

- 런타임 상태(`providerSessionId`, 방문 횟수 등)는 모델에 넣지 않는다. 05의 `RunState`에서 관리.
- 모델 슬러그 유효성은 여기서 검사하지 않는다(프리플라이트에서 CLI 조회 결과로 검사).
- 모델·effort 비용 정책은 검증용 실행을 준비하는 작업자에게 적용한다. 사용자 YAML을 저비용 모델로 자동 치환하지 않으며 모델 테스트는 CLI 호출 없이 수행한다.
