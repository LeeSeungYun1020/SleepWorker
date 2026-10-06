# Phase 2 구현 결과

구현 범위: `05-engine.md`. 검증일: 2026-10-05, macOS / JDK 21.

## 구현

- `engine/RunOrchestrator`: 저장된 버전을 다시 로드하고 명시적 start/전이만 실행.
  StateFlow 상태, SharedFlow 로그, 일시정지/재개, Ask의 Retry/Skip/Abort,
  사용자 중지와 앱 종료 구분. 제어 요청과 상태 기록은 공유 mutex로 직렬화한다.
- `StepRunner`, `CommandRunner`, `ExecutionIO`: stdout/stderr 동시 수신과 단일 소비자
  파싱, 종료 후 기본 5초 drain, 본문 timeout, 최종 파싱, provider·exit·completion의
  결합 판정. 프로세스 정리 및 I/O 작업 대기도 제한하며 정리 불확실 시 추가 시작 차단.
- `RetryPolicy`: 자동 재시도는 같은 방문의 두 번째 시도만 허용. agent는 이번 방문의
  ID로 resume, shell은 동일 본문. 수동 Retry는 원래 스크립트로 새 방문을 만든다.
  NEW 초기화, RESUME ID 필수, 거부된 ID 제거, provider/workspace 결합을 적용한다.
- `CheckRunner`, `CompletionChecker`, `TransitionEvaluator`: 파일/glob/내용/명령 검사,
  완료 명령과 전이 명령의 nonzero 의미 구별, 외부 조건 캐시, 첫 일치 우선순위,
  maxVisits/maxSteps, 실제 대상 진입 시에만 Next 전이 계수·세션 초기화.
- `git/WorktreeManager`: 관리 대상 Git 명령으로 생성·기존 브랜치 사용·경로/브랜치
  재확인·dirty 검사·제거. 준비 실패는 본문 실행 없이 기록한다.
- `RunRecorder`, `RunHistory`, `RunRecovery`: 원자적 run.json, 방문/시도별 원문과 결과,
  검사·준비 명령 기록, 불변 결과 보호, 저장 경로 symlink 거부 및 소유권 검사.
  이전 비종료 런을 멱등하게 INTERRUPTED로 표시하고 프로세스는 실행하지 않는다.
- 기존 모델의 조건 serializer에 JSON 읽기를 추가해 YAML의 실행 정의를 run.json에
  보존/복원한다. provider report/event 직렬화와 CLI 제공 usage 보존도 추가했다.

## 검증 결과

`./gradlew build`: **성공**. 전체 **80개 테스트 성공**, 실패/오류/건너뜀 0.
기존 Phase 1 33개에 Phase 2 47개를 추가했다.

| 테스트 | 개수 | 검증 범위 |
|---|---:|---|
| EngineTest | 22 | 명시적 그래프, 순서/좌표 독립성, 세션, retry, Skip, pause, 상한, 기록, 기본 복구 |
| EngineBoundaryTest | 17 | EOF/drain/I/O, agy 취소·timeout과 관측 exit 1, 검사/준비/프리플라이트 취소, worktree, 세션 결합, 기록 실패, 정리 불응답 |
| EngineRecoveryTest | 6 | 대기 중 미진입 전이 계수, 모든 비종료 상태 복구, 새 런 초기화, 파일 completion retry, Skip의 새 조건, 실행 중 편집/복원 |
| EngineIntegrationTest | 2 | 로컬 zsh의 리뷰 첫 줄·SHA 분기, 임시 Git 저장소의 worktree 생성/재사용/dirty/제거 |

- 모델 호출 **0회**, 인증 변경·네트워크 게시 **없음**.
- 엔진/Git 공통 코드에 JVM 전용 import 없음. `git diff --check` 통과.
- 정상 종료·provider 실패·출력 미완료·timeout·취소를 분리했다. 관측되지 않은
  exitCode와 비정상 종료 시각은 생성하지 않는다.
- 일시정지 후 End는 COMPLETED, Ask는 AWAITING_USER, Next만 PAUSED.
  Next를 실제로 방문하기 전 abort하면 전이 횟수는 증가하지 않는다.
- permission denied, 정리 timeout, 취소 불응답을 주입해 FAILED와 후속 실행 0회를
  검증했다. 앱은 종료를 확인할 수 없는 외부 프로세스를 종료됐다고 표시하지 않는다.
- 초기 기록 실패 시 시작 0회, 출력 기록 실패 시 정리 및 재시도 차단,
  이미 확정된 결과의 덮어쓰기 거부를 검증했다.

## 연결 API와 다음 단계

- `RunOrchestrator.start(version, settings)`는 저장 버전 ID로 재로딩하고 종료까지 대기한다.
  앱 scope에서 실행하며 `state`와 `logs`를 구독한다. 인스턴스 하나가 런 하나를 소유한다.
- `WorkflowStore`와 `RunRecorder`는 같은 `RepositoryLease`를 사용한다. 저장소를 열 때
  먼저 잠금을 확보하고 `RunRecovery.recover()` 후 새 런을 허용한다. 앱 종료 시
  `interruptForShutdown()`을 기다린 다음 잠금을 해제한다.
- attempt의 종료코드/provider/completion/failure는 `AttemptRecord.result`에 함께 저장한다.
  transitionCounts의 키는 `stepId:0기반 인덱스`, visit/attempt는 1부터 시작한다.
- Phase 3는 실행 화면과 실제 CLI 프리플라이트를 연결한다. 현재 `Preflight.None`은
  Phase 2의 no-op 연결 지점이다. 실측된 binary version/contract ID를 주입하며,
  실제 모델/usage/가격을 추정하지 않는다.
- 이전 합의의 agy 미로그인 실측과 추가 모델/effort 검증은 계속 Phase 3 범위다.
  UI 연결, DMG 재생성, 실모델 end-to-end 실행은 이번 검증 범위가 아니다.
- 저장 장치 오류로 최종 상태까지 쓸 수 없으면 메모리 상태와 앱 진단에 FAILED를
  남기고 마지막 내구성 있는 기록을 보존한다. 재시작 복구는 그 기록을 기준으로 하며
  완전한 로그나 외부 프로세스 종료를 보장하지 않는다.
