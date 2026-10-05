# 06 — 실행 화면 + 실제 CLI 연결 + 프리플라이트 (Phase 3)

선행: `05-engine.md` | 다음: `07-workflow-editor.md`

## 목표

실제 Codex/Antigravity/셸을 엔진에 연결하고, 프리플라이트를 구현하며, 실행 화면(Compose, `commonMain/ui/run`)에서 계획서 §4 예시 워크플로를 end-to-end로 자동 실행한다.

## 작업 항목

### 1. 프리플라이트 (`engine/Preflight.kt`)
순서대로 수행, 하나라도 ERROR면 실행 차단. 결과 `List<PreflightItem(name, status: OK|INFO|WARN|ERROR, detail)>`
- [ ] CLI 경로: 설정값 없으면 `PathDetector.detect` → 없으면 ERROR "codex를 찾을 수 없음, 설정에서 경로 지정"
- [ ] 선택한 절대 경로·버전을 함께 표시. PATH 0.146.0/앱 번들 0.160.0처럼 복수 설치가 있으면 선택한 바이너리를 검사하며 자동 업데이트/조용한 교체 없이 호환 경로 지정 안내
- [ ] CLI 버전/지원 기능: 01에서 검증한 계약과 요청한 new/resume/model/effort의 호환 확인. 미지원 기능이나 호환을 확인하지 못한 버전이면 ERROR와 재검증 안내, 옵션·모델을 조용히 대체하지 않음
- [ ] 로그인: 워크플로에 등장하는 provider만 `probeAuth`. LoggedOut이면 ERROR "로그인 필요", Unknown이면 ERROR "인증 상태 확인 불가"와 네트워크/옵션/버전 진단. agy 미로그인 계약 미검증을 단순 로그아웃으로 표시하지 않음. 앱의 로그인/강제 로그아웃 기능 없음
- [ ] 모델 슬러그: Antigravity → `listModels` 결과에 포함 여부, Codex → 설정 리스트 포함 여부(미포함은 WARN)
- [ ] 워크플로 검증: `WorkflowValidator` ERROR 있으면 차단(명시적 start·모든 단계의 전이 필수), WARNING 표시. 배열 순서·노드 위치로 누락 연결을 보완하지 않음
- [ ] 저장소: 경로 존재·git repo 여부(ERROR), `isDirty` → WARN
- [ ] 워크트리: 정의된 워크트리가 이미 존재하면 "재사용" 안내(INFO), 브랜치 충돌 가능성 WARN
- [ ] branch 이름은 Git으로 검증하고 기존 worktree의 실제 경로·브랜치가 정의와 다르면 ERROR. 표시 이름/step id를 임의의 파일 경로로 사용하지 않음
- [ ] `git`, `gh` 존재 확인(`gh`는 WARN)

### 2. 실행 ViewModel (`ui/run/RunViewModel.kt`)
- [ ] `RunOrchestrator`를 보유, `RunState` StateFlow → UI 상태로 매핑
- [ ] 로그 `SharedFlow`를 visitNo·attemptNo·phase별로 구분(메모리 상한: visit당 20,000줄, 초과 시 앞부분 버리고 "파일에서 전체 보기" 안내). 자동 재시도는 같은 방문의 시도, 수동 다시 시도는 연결된 새 방문으로 표시
- [ ] 액션: `runPreflight(version)`, `start(version)`, `pause`, `resume`, `abort`, `answer(UserDecision)`. 선택한 저장 버전의 workflowId/versionId를 표시하고 프리플라이트 결과를 해당 버전에 연결. 다른 버전 선택 시 재검사
- [ ] 완료/실패/AWAITING_USER 전이 시 `Notifier.notify`

### 3. 실행 화면 (`ui/run/RunScreen.kt`)
레이아웃: 상단 툴바 / 좌측 타임라인 / 우측 로그
- [ ] 툴바: `WorkflowStore`의 워크플로·저장 버전 선택, [프리플라이트] [실행] [일시정지] [재개] [강제 중지], 런 상태 배지, 경과 시간. 초안은 실행 대상에서 제외, 유효 버전이 없으면 편집·저장 안내
- [ ] 프리플라이트 패널: 항목별 OK/INFO/WARN/ERROR 아이콘 + 상세, ERROR 있으면 [실행] 비활성
- [ ] 타임라인: **실제 방문 순서** 기준 리스트(편집기 목록·노드 위치와 무관). 각 항목: visitNo, 단계 id·title, kind 아이콘(agent/`!`), 상태 색상, 소요 시간, 세션 ID(클릭 복사), attempt 표시(재시도 시 "2/2"), 실행 스냅샷 기준 선택 전이의 우선순위·조건·대상(`2: failure → fix`)
- [ ] 로그 패널: 선택 visit의 stdout/stderr 토글, 자동 스크롤(하단 고정 토글), agent 단계는 "이벤트 요약" 뷰(Message/ToolCall만) ↔ "원문" 뷰 전환, shell 단계는 터미널 원문 뷰만
- [ ] 일시정지 상태 표시: "현재 단계 완료 후 정지합니다" (PAUSE_REQUESTED) / "일시정지됨 — 다음: <stepId>" (PAUSED)
- [ ] AWAITING_USER 모달: 명시적 ask / 미매칭 / 조건 평가 오류 / maxVisits / maxSteps 구분, 최종 실패 원인·자동 재시도 불가 사유·로그 tail 표시. [다시 시도]는 원래 스크립트의 새 방문이며 전체 단계가 재실행됨을 안내하고 maxSteps 소진 시 비활성
- [ ] [건너뛰기]는 원래 실패 기록을 보존한 채 success로 간주하여 전이를 평가. 이미 평가한 외부 조건은 재실행하지 않으며 오류·ask·상한이 남으면 대기 유지. 오류 조건만 다시 검사하는 버튼으로 오해하지 않게 안내
- [ ] 로그 패널에 시도 선택과 본문/완료 확인/전이 조건 구분, provider 결과·실제 종료코드·프로토콜 오류·시간 초과를 각각 표시. 세션 ID 미확보 시 이전 방문 ID를 표시하지 않음
- [ ] 강제 중지 확인 다이얼로그
- [ ] 재시작 시 INTERRUPTED 런을 기록 화면으로 표시하고 [재개]·[다시 시도] 비활성. [새 실행]은 버전 선택·프리플라이트를 거쳐 새로운 런을 start부터 시작

### 4. 실제 CLI 배선 (`desktopApp/Main.kt` → DI)
- [ ] `JvmProcessExecutor`, `JvmTempFiles`, `MacNotifier`, `ZshPathDetector`, okio `FileSystem.SYSTEM`, 설정 파일 경로 주입
- [ ] 설정 로드 실패 시 기본값으로 시작
- [ ] 저장소 열기 시 소유권 잠금 확보 → `RunRecovery` 실행 → 실행/히스토리 화면에 중단 기록 반영. 복구 처리는 CLI 실행을 호출하지 않음

### 5. End-to-end 검증
- [ ] 00의 비용 원칙에 따라 파서·분기·SHA·GitHub 상태 비교는 fixture/셸로 먼저 확인. 실제 모델이 필요한 new/resume·작은 구현·리뷰만 지원 확인한 GPT-6 Luna 등 저비용 모델과 low effort를 명시 사용. 복잡한 품질 검증만 사유를 기록해 상향
- [ ] 검증 도우미에 provider별 모델/effort 인자를 두고 고비용 모델 고정/사용자 기본 모델 상속을 제거한 뒤 실모델 테스트 시작. 호출 수·usage·모델 선택 이유를 검증 기록에 포함
- [ ] 01에서 쓴 샘플 저장소에 계획서 §4 예시(실제 모델 슬러그로 치환)를 `WorkflowStore`로 가져와 초안·첫 버전 생성. 07 편집기 구현 전에는 검증용 fixture/도우미로 동일 저장소 API 사용
- [ ] 작은 샘플 저장소로 기본 흐름을 검증하고 GitHub 통합은 사용자가 지정한 저장소의 독립 clone/worktree·전용 브랜치에서만 수행. 기존 Manicule 체크아웃/업무 이슈를 변경하지 않음. Phase 0 PR #113 및 원문은 비교 근거이며 앱 end-to-end 성공 증거로 재사용하지 않음
- [ ] 앱에서 프리플라이트 → 실행 → sync(!) → plan → implement → verify → prepare-review(!) → review → … → publish → log(!) → end 까지 **무개입 완주**
- [ ] 의도적 실패 시나리오: verify의 `completion: command` 실패 → 재시도 → `failure → fix` 전이 확인
- [ ] implement/fix가 실제 커밋을 만들고 prepare-review가 현재 head/base를 고정하는지 확인. 검토 전 코드를 커밋하고 보고서는 로컬 exclude로 분리하는 예시 준비 절차 적용
- [ ] 리뷰 첫 줄 CHANGES_REQUESTED + 본문의 APPROVED → fix, 오래된 SHA/빈 리뷰/형식 오류→ask, 검토 후 branch 이동/dirty 작업 트리→publish 진행 차단. prepare-review가 이전 리뷰를 매번 무효화하는지 확인
- [ ] publish 직전 현재 원격 base·로컬 HEAD·리뷰 SHA·clean 상태·게시 계정을 재확인. 읽기 권한/성공만으로 게시 계정을 추정하지 않음. 공유 `gh` 활성 계정 자동 전환 금지; 사전 준비한 실행 환경을 사용하며 앱 설정/argv/로그에 토큰 저장 금지
- [ ] 허용된 GitHub 테스트에서 승인된 SHA만 push → 원격 SHA 확인 → 동일 head/base의 열린 draft PR 조회 후 생성 또는 재사용 → 반환 URL/상태·CI 확인. 검증용 PR은 병합 없이 닫고 소유/미변경 SHA 확인 후 테스트 원격 브랜치 정리. 업무용 PR에는 테스트 정리 동작 적용 금지
- [ ] 예시의 `steps` 배열을 재정렬하고 `editor.nodes` 좌표를 변경한 YAML도 동일한 입력·결과 조건에서 같은 방문 순서/전이 선택. 명시적 start/end가 유지됨을 확인
- [ ] 일시정지 → 현재 단계 종료 후 정지 → 재개 확인
- [ ] 강제 중지 → agent 프로세스 트리가 남아 있지 않음(`ps` 확인)
- [ ] 완료 확인/전이 명령 실행 중 강제 중지→보조 프로세스까지 종료·후속 실행 없음, 전이 검사 timeout/exit 2→조건 오류 대기, pause와 End/Ask가 겹치면 각각 완료/사용자 대기
- [ ] 별도 미로그인 계정/VM의 실측 fixture 및 통합 환경에서 LoggedOut 차단 확인. 현재 사용자의 인증 삭제/로그아웃으로 만들지 않음. 네트워크/프로브 오류는 Unknown으로 별도 확인
- [ ] 완료 후 `.aiflow/runs/<id>/` 내용 확인, 터미널에서 기록된 세션 ID로 `codex exec resume <id>` / `agy --conversation <id>` 수동 이어가기 가능 확인
- [ ] 앱 재시작 시 미완료 런이 INTERRUPTED로 표시되고 자동 실행이 없음. 과거 런에서 사용 버전 확인, 해당 버전으로 새 런을 명시적으로 시작 가능

## 산출물

- `engine/Preflight.kt`, `ui/run/*`, DI 배선
- 샘플 저장소의 `.aiflow/workflows/<workflowId>/draft.yaml`, `versions/<versionId>.yaml`
- end-to-end 실행 기록(`.aiflow/runs/<id>/`) 1건

## 완료 기준

- [ ] 예시 워크플로가 앱에서 end-to-end 자동 완주
- [ ] 재시도·전이·일시정지·강제 중지·미로그인 5개 시나리오 수동 확인
- [ ] 실행 중 UI가 멈추지 않음(로그 스트리밍이 메인 스레드를 막지 않음)
- [ ] 첫 시도 실패 후 자동 재시도 성공 및 수동 재방문의 원문·결과가 각각 보존되고, provider 실패를 종료코드 0만으로 성공 처리하지 않음
- [ ] 타임라인이 명시적 화살표를 따른 실제 방문 기록과 일치하고, 그래프 정의 오류는 실행 전에 차단됨
- [ ] 검증별 모델/effort·선택 이유·호출 수 및 실측/synthetic 출처가 기록되고, 기존 통과 항목을 불필요하게 재호출하지 않음

## 주의

- 긴 로그에서 Compose `LazyColumn` 성능: 라인 리스트는 불변 스냅샷으로 교체, 디바운스(100ms) 적용.
- 프리플라이트의 `probeAuth`·`listModels`는 네트워크를 탈 수 있으므로 타임아웃(30초) 적용.
