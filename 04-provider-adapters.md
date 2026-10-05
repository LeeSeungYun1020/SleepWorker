# 04 — Provider 어댑터 (Phase 1c)

선행: `01-verify-cli.md`, `02-create-project.md` | 다음: `05-engine.md` (03과 병행 가능)

## 목표

Codex / Antigravity / Shell 세 어댑터를 `ProviderAdapter` 인터페이스로 구현한다. 어댑터는 **커맨드 빌드와 출력 파싱만** 담당하는 순수 로직이며 `ProcessExecutor`를 통해서만 실행한다. 01의 `phase0-result.md`에 기록된 실제 플래그·포맷을 반영한다.

## 작업 항목

### 1. 인터페이스 (`provider/ProviderAdapter.kt`)
아래 시그니처를 계획서 §5와 공통 계약으로 사용한다. 파서 상태는 **시도마다 새로 생성**하고 provider 인스턴스에 출력 버퍼를 공유하지 않는다.

```kotlin
interface ProviderAdapter {
    val id: Provider? // shell은 null
    fun buildCommand(req: ExecRequest): ProcessSpec
    fun createParser(req: ExecRequest): ProviderParser
    suspend fun probeAuth(exec: ProcessExecutor, cfg: ProviderConfig): AuthStatus
    suspend fun listModels(exec: ProcessExecutor, cfg: ProviderConfig): List<String>?
}
interface ProviderParser {
    fun accept(line: String, stream: Stream): List<AgentEvent>
    fun finalizeOutput(exitCode: Int?, termination: Termination): ProviderReport
}
data class ExecRequest(val cwd: String, val script: String, val model: String?, val effort: Effort?,
                       val resumeSessionId: String?, val binaryPath: String, val scriptFilePath: String?)
enum class ProviderOutcome { SUCCEEDED, FAILED, PROTOCOL_ERROR, NOT_APPLICABLE }
enum class Termination { NORMAL, TIMED_OUT, CANCELLED, START_FAILED, OUTPUT_INCOMPLETE }
data class ProviderReport(val outcome: ProviderOutcome, val sessionId: String?,
                          val finalOutput: String?, val failure: FailureInfo?, val finalEvents: List<AgentEvent>)
// AgentEvent: SessionStarted, Message, ToolCall, Diagnostic, Raw, Completed, Failed
```

- [ ] `accept`는 스트리밍 이벤트를, `finalizeOutput`은 남은 최종 이벤트와 시도 전체 판정을 반환. finalizeOutput은 stdout/stderr 수신 종료 후 정확히 한 번 호출하며 finalEvents에 이미 방출한 이벤트를 중복하지 않음
- [ ] 결과 우선순위: 알려진 명시적 최종 실패 → FAILED, 요구된 응답이 누락/손상됨 → PROTOCOL_ERROR, 검증된 성공 계약 만족 → SUCCEEDED. shell은 NOT_APPLICABLE이며 엔진이 종료코드를 판정. 성공 이벤트가 나와도 이미 확정한 실패를 뒤집지 않음
- [ ] 일반 로그의 `error` 문자열·stderr 출력 자체는 작업 전체 실패가 아님. `Diagnostic`과 작업 종료를 의미하는 `Failed`를 구별하고 01에서 검증한 구조만 최종 결과로 해석
- [ ] 알려지지 않은 일반 로그는 Raw로 보존. 필수 응답 JSON/종료 이벤트 손상과 구분하며, 검증된 성공 계약을 확인할 수 없으면 성공으로 추정하지 않음
- [ ] 세션 ID는 new에서 이번 시도 출력으로만 획득. resume은 검증된 입력 ID를 유지할 수 있으나 provider가 해당 세션을 거부하면 SESSION_INVALID로 표시. 서로 다른 세션 ID가 충돌하면 PROTOCOL_ERROR
- [ ] 새 세션 실행이 성공 응답을 내도 필수 세션 ID가 없으면 PROTOCOL_ERROR. 빈 agent 출력 허용 여부는 01의 계약에 따르며 shell의 빈 출력은 정상 허용
- [ ] `FailureInfo(kind, phase, detail)`의 공통 kind는 PREPARATION, AUTH, CONFIG, SESSION_MISSING, SESSION_INVALID, PROVIDER, PROTOCOL, EXIT_CODE, TIMEOUT, OUTPUT_IO, COMPLETION. phase는 PREPARING/EXECUTING/FINALIZING/CHECKING. 취소는 실패 재시도 대상으로 변환하지 않음
- [ ] 필요한 옵션/기능이 UNSUPPORTED 또는 계약 미검증이면 설정/프리플라이트 오류. 옵션을 버리거나 다른 모델·세션으로 조용히 대체하지 않음
- [ ] `AuthStatus`는 LoggedIn / LoggedOut / Unknown(detail) / NotApplicable을 구분. 네트워크·버전·옵션·응답 파싱 실패는 로그인 실패로 단정하지 않으며 원문/진단 보존

### 2. `CodexAdapter`
아래 provider별 argv/이벤트 매핑은 01 결과로 확정할 후보이며, 지원 버전·기능 계약과 함께 구현한다.
2026-10-05 실측 자료는 `scripts/samples/codex/bundled-live/`(0.160.0) 참조.
`scripts/samples/codex/live/`(0.146.0)는 기본 모델의 CLI 버전 불일치 실패 자료다.
`scripts/phase0-result.md`의 미검증 항목을 어댑터 지원 기능으로 가정하지 않는다.
- [ ] new: `[bin, "exec", "--json", "-C", cwd, "-m", model, "-c", "model_reasoning_effort=<effort>", "-c", "sandbox_mode=danger-full-access", "-c", "approval_policy=never", "-"]`, `stdin = script`
- [ ] resume: `[bin, "exec", "--json", "-C", cwd, "-c", ...(동일 3개), "resume", sessionId, "-"]`, `stdin = script` — `-m` 포함 여부는 01 결과에 따름
- [ ] 시도별 파서 `accept`: JSONL 파싱. `thread.started` → `SessionStarted(thread_id)`, 메시지/아이템 이벤트 → `Message`, `turn.completed` → `Completed`, `turn.failed` → `Failed`, 일반 로그는 Raw, 필수 구조 손상은 최종 PROTOCOL_ERROR
- [ ] `probeAuth`: `codex login status` 종료코드 0 → LoggedIn
- [ ] `listModels`: `null` (설정의 수동 리스트 사용)

### 3. `AntigravityAdapter`
2026-10-05 실측 자료: `scripts/samples/agy/live/`, 버전 1.2.16.
추가 자료: `scripts/samples/additional/manicule-v2/`. 다른 cwd의 명시 ID resume에서도
실제 실행은 원래 워크트리에 유지됨. 강제 중단은 exit 1 + JSON status ERROR /
`context canceled`를 반환했으므로 종료코드만으로 취소 여부를 추론하지 않는다.
- [ ] new: `[bin, "-p", script, "--output-format", "json", "--model", model, "--effort", effort, "--dangerously-skip-permissions"]`, `cwd = 워크스페이스`
- [ ] resume: 위 + `["--conversation", sessionId]`
- [ ] stdout 전체가 하나의 JSON인 계약이면 시도별 파서에 누적한 뒤 `finalizeOutput`에서 파싱. `conversation_id` → SessionStarted, `response` → Message를 finalEvents로 반환. stderr의 구조화 오류도 계약에 따라 Diagnostic 또는 최종 FAILED로 분류
- [ ] 검증된 계약이 JSON 응답을 요구하면 빈 stdout·부분 JSON은 종료코드 0이어도 PROTOCOL_ERROR. 성공 여부와 무관하게 확보한 유효 세션 ID는 반환
- [ ] 최종 `status == "SUCCESS"`를 확인. `status == "ERROR"` 또는 `error`가 있으면 실패. 실측 잘못된 모델은 종료코드 1이며 stderr는 `AGY_ERROR` 접두가 없는 일반 텍스트. 3이라는 특정 코드나 접두에 의존하지 않음
- [ ] `probeAuth`: `agy models` 조회 성공 관찰. 미로그인 실측 전에는 실패를 무조건 LoggedOut으로 변환하지 않고 Unknown/진단으로 표시
- [ ] `listModels`: `agy models` stdout의 탭 구분 `<slug>\t<label>`에서 첫 필드 추출. `--output-format json`은 해당 버전에서 UNSUPPORTED

### 4. `ShellAdapter`
- [ ] 단일 줄: `["/bin/zsh", "-lc", body]`
- [ ] 여러 줄: 엔진이 생성한 `req.scriptFilePath`로 `["/bin/zsh", "-l", path]` 생성. 어댑터는 파일 I/O를 수행하지 않으며 임시 파일 생성·정리는 엔진 책임
- [ ] 파서 `accept`: 모든 라인 → Raw. `finalizeOutput`: NOT_APPLICABLE, sessionId = null
- [ ] `probeAuth` → NotApplicable, `listModels` → null
- [ ] 시간 제한과 취소는 엔진에서 처리

### 5. `ProviderRegistry`
- [ ] `fun adapterFor(step: Step, workflow: Workflow): ProviderAdapter` — effectiveKind가 SHELL이면 ShellAdapter, 아니면 세션의 provider

- [ ] `VerifiedCliContract`에 01에서 확인한 버전·옵션·출력 계약을 패키징하고 프리플라이트에서 바이너리 버전/요청 기능과 대조. 런 기록에도 실제 바이너리 버전과 적용 계약 식별자를 남김
- [ ] 기능별 VERIFIED/UNSUPPORTED/NOT_VERIFIED와 근거 fixture 경로를 보존. help의 effort 표기, 모델 목록 포함, 실제 해당 모델+effort 성공을 서로 다른 근거로 취급. 버전 문자열만 같다는 이유로 모든 모델 조합을 지원 처리하지 않음

### 6. 설정 모델 (`storage/AppSettings`)
- [ ] `codexPath`, `agyPath`, `codexModels: List<String>`(편집 가능한 후보 목록, 지원 보장 아님), `agyModelsCache`(binary 경로/버전·조회 시각 포함), `defaultWorktreeRoot`, `notificationsEnabled`
- [ ] JSON으로 `~/Library/Application Support/aiflow/settings.json` 저장 (경로는 jvmMain에서 주입)

### 7. 테스트 (`commonTest`)
- [ ] Codex new/resume 커맨드 배열이 기대값과 정확히 일치, stdin == script
- [ ] Codex JSONL 샘플(01에서 저장한 원문) 파싱 → thread_id 추출, Completed 감지, turn.failed → Failed
- [ ] Antigravity new/resume 커맨드, JSON 샘플 파싱 → conversation_id, 구조화 최종 오류 → FAILED, 계약상 필수 출력 누락 → PROTOCOL_ERROR
- [ ] Shell: 단일 줄 `-lc`, 여러 줄 → 임시 파일 경로, cwd가 워크트리 경로
- [ ] `ProviderRegistry`: `!` 스크립트 → ShellAdapter

- [ ] 종료코드 0 + 명시적 실패, 성공 이벤트 뒤 최종 실패, 필수 종료 응답 누락/손상은 성공 판정 불가
- [ ] 종료 후 최종 파싱에서만 ID/결과가 나오는 fixture → finalEvents와 report에 반영, 중복 방출 없음
- [ ] 일반 stderr 경고·미지의 로그와 최종 실패 구별. shell 빈 출력 정상. 두 시도/두 파서 간 누적 출력 격리
- [ ] 미지원 옵션은 명확한 CONFIG 오류. 실측 fixture와 synthetic fixture의 출처 표시
- [ ] `codex/bundled-live`, `agy/live`, `additional/manicule-v2`에서 new/resume·미인증 Codex 401·잘못된 모델·agy context canceled/exit 1·TSV 모델 목록을 가져와 오프라인 회귀 테스트. 불완전한 `agy-interrupt/`에는 관측되지 않은 exitCode를 추가하지 않음
- [ ] agy 미로그인 fixture는 확보 전까지 NOT_VERIFIED로 별도 표시. 모델 조회의 네트워크 오류/잘린 응답은 Unknown이며 LoggedOut으로 오분류하지 않음을 테스트
- [ ] 모델 비용 없이 원문 재생으로 파서 검증. 새 모델 계약 실측이 필요할 때만 00의 저비용 우선 원칙과 01 도우미를 적용

## 산출물

- `provider/ProviderAdapter.kt`, `CodexAdapter.kt`, `AntigravityAdapter.kt`, `ShellAdapter.kt`, `ProviderRegistry.kt`
- `storage/AppSettings.kt`
- 테스트 + `commonTest/resources/samples/` (01에서 받은 원문 출력)

## 완료 기준

- [ ] 모든 어댑터 테스트 통과
- [ ] 01의 실제 출력 샘플로 파싱 테스트가 작성됨(가짜 샘플만으로 끝내지 않음)
- [ ] 어댑터 코드에 `ProcessBuilder`, `java.*` import 없음(commonMain 순수성)

## 주의

- 프롬프트(script)는 가공 없이 그대로 전달한다. 트리밍조차 하지 않는다(선두 `!` 제거는 shell 단계에 한함).
- Antigravity `--continue`는 사용하지 않는다. 항상 `--conversation <id>`.
