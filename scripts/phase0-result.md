# Phase 0 검증 결과

실행일: 2026-10-05 (Asia/Seoul). result.json의 시각은 UTC.

후속 계획은 `00-overview.md` 및 `airflow.md`에 반영했다. 이후 모델 검증은 GPT-6 Luna 등
저비용 모델 우선/명시 선택 원칙을 적용하지만, 아래 과거 실측 모델·fixture는 그대로 유지한다.
Luna의 설치 CLI 지원은 아직 별도 실측 전이며 기존 Astra 성공으로 대체하지 않는다.

**전체 Phase 0: NOT_VERIFIED — 핵심 new → ID → resume 계약은 두 provider 모두
통과했으며 Manicule GitHub 게시·PR 재사용·CI 검증까지 추가 완료했다. agy 미로그인
실패 계약을 포함한 아래 미검증 항목이 남아 있다. Phase 1 진행 게이트는
아직 완료로 표시하지 않는다.**

## 실행 환경 / 범위

| 대상 | 바이너리 / 버전 | 결과 |
|---|---|---|
| Codex PATH | `/Users/leeseungyun/.nvm/versions/node/v24.11.1/bin/codex`, 0.146.0 | 기본 `gpt-6-astra` 실행 거부: newer version required. `codex/live/` |
| Codex 앱 번들 | `/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex`, 0.160.0 | 워크트리 new/resume VERIFIED. `codex/bundled-live/` |
| Antigravity | `/Users/leeseungyun/.local/bin/agy`, **1.2.16** | `gemini-3.8-flash-low`, effort low, new/resume VERIFIED. `agy/live/` |

초기 수동 조회의 agy 버전은 1.1.28이었지만 정식 수집 실행의 `version/stdout.log`는
1.2.16이다. 설치/업데이트 명령을 수행하지 않았으며 변화 원인은 확인하지 않았다.
성공 fixture의 계약을 1.1.28에 소급하지 않는다.
PATH 및 사용자 설정은 변경하지 않았다. Phase 0 검증 당시 프로젝트 루트는 Git 저장소가 아니었으므로
해당 검증 실행에서는 프로젝트 커밋을 만들지 않았다. 테스트 전용 저장소/워크트리만 별도로 생성했다.

## provider 계약표

| 계약 | Codex 0.160.0 | agy 1.2.16 |
|---|---|---|
| new argv | `exec --json -C <worktree> -c model_reasoning_effort=low -c sandbox_mode=danger-full-access -c approval_policy=never -` | `--output-format json --effort low --dangerously-skip-permissions --model gemini-3.8-flash-low -p <prompt>` |
| resume argv | 동일 전역 옵션 뒤 `resume <thread_id> -` | 동일 옵션 + `--conversation <id> -p <prompt>` |
| stdin / cwd | UTF-8 prompt, write 후 close / 샘플 worktree | 빈 stdin close, prompt는 argv / 샘플 worktree |
| 모델/effort | 기본 설정 및 명시적 `-m gpt-6-astra` + low 실측 VERIFIED. 다른 조합은 NOT_VERIFIED | gemini-3.8-flash-low + low 실측. 전체 조합 지원은 NOT_VERIFIED |
| ID | 초기 `thread.started.thread_id` | 최종 JSON `conversation_id` |
| 출력 | JSONL 여러 chunk, `item.completed`의 `agent_message.text`, `turn.completed` | stdout 단일 JSON, `status: SUCCESS`, `response` |
| 성공 판정 | 실제 exit 0 + complete output + turn.completed + ID + 파일·문맥 확인 | 실제 exit 0 + complete JSON + status SUCCESS + ID + 파일·문맥 확인 |
| 실패 판정 | `error`, `turn.failed`, 실제 exit 1 | JSON status ERROR / error, 일반 stderr, 실제 exit 1 |
| 빈 agent 결과 | 허용 여부 NOT_VERIFIED. 수집기의 핵심 계약 검증은 문맥·파일 검사까지 필요 | 허용 여부 NOT_VERIFIED. 빈 JSON/본문을 검증 성공으로 취급하지 않음 |
| 재개 문맥 | 토큰을 재전달하지 않고 동일 토큰을 답함, 동일 ID 유지, FILES.md 수정 | 동일 방식 VERIFIED |
| 모델 조회 | 이 단계에서 API/모델 목록 전체 탐지 미구현 | `agy models` → 탭 구분 slug/label. JSON 옵션 UNSUPPORTED |
| 미지원 기능 | resume 뒤 `--sandbox` → exit 2 | `models --output-format json` → exit 1 |

정확한 절대 경로, 입력, argv, 원문, 자체 종료코드와 시각은 각 case의 result.json 및
stdin.txt가 기준이다. 성공한 문서 내용의 품질 전체가 아니라 세션/실행 계약을 검증한다.

## 핵심 실행 결과

| provider / case | 세션 ID | 종료코드 | 소요 초 |
|---|---|---:|---:|
| Codex new | `01a109c5-0c63-7b93-bd78-773da75e8369` | 0 | 20.229 |
| Codex resume | 동일 | 0 | 11.242 |
| Codex invalid-model | `01a109c5-873f-7ee3-a032-257091da4f96` | 1 | 2.089 |
| agy new | `a6a5569c-894c-413a-8b64-e852b7ec68fb` | 0 | 13.902 |
| agy resume | 동일 | 0 | 9.234 |
| agy invalid-model + effort low | 빈 ID | 1 | 3.769 |

Codex JSONL 원문 발췌 (`samples/codex/bundled-live/new/stdout.log`):

```jsonl
{"type":"thread.started","thread_id":"01a109c5-0c63-7b93-bd78-773da75e8369"}
{"type":"item.completed","item":{"id":"item_3","type":"agent_message","text":"READY"}}
{"type":"turn.completed","usage":{"input_tokens":55265,"cached_input_tokens":44288,"cache_write_input_tokens":0,"output_tokens":244,"reasoning_output_tokens":20}}
```

agy JSON 원문 (`samples/agy/live/new/stdout.log`):

```json
{"conversation_id":"a6a5569c-894c-413a-8b64-e852b7ec68fb","status":"SUCCESS","response":"READY\n","duration_seconds":8.999806,"num_turns":1,"usage":{"input_tokens":42580,"output_tokens":178,"thinking_tokens":0,"cache_read_tokens":0,"total_tokens":42758}}
```

agy invalid-model의 stderr는 `error: invalid model selection ... --effort is not
supported for model ...`이다. 따라서 **잘못된 모델+effort 조합**의 실패를 실측한 것이며,
effort를 생략한 모델 검증 순서/오류까지 동일하다고 가정하지 않는다.

## 추가 실험

| 항목 | 상태 / 근거 |
|---|---|
| Codex 로그인 | VERIFIED: auth exit 0. 사용자 인증 원문/토큰은 수집하지 않음 |
| Codex 미로그인 status | VERIFIED: 별도 CODEX_HOME + file credential store + API 키 환경 제거, exit 1, stderr `Not logged in`. 사용자 로그아웃 없음 |
| Codex 미로그인 exec 자체 오류 | VERIFIED: 격리 CODEX_HOME에서 401 / turn.failed / exit 1. `additional/manicule-v2/codex-unauthenticated-exec/` |
| 워크트리 생성 / cwd | VERIFIED: 기존 샘플 및 Manicule 독립 복제본의 origin/main 기반 워크트리 |
| 로그인 셸 PATH | VERIFIED: login-shell-path 원문에 각 바이너리 경로 |
| 로그인 셸 git / 여러 줄 파일 | VERIFIED: shell-login / shell-multiline, exit 0 |
| `.zshrc` alias 배제 | VERIFIED: 별도 ZDOTDIR에서 `zsh -ic` alias 조회 exit 0, `zsh -lc` exit 1. 사용자 셸 설정 변경 없음 |
| stdin 미닫힘 | VERIFIED (제한된 관찰): Codex 0.160.0에서 5초간 출력 없음, 수집기가 SIGTERM → -15. 무한 대기를 증명한 것은 아님 |
| ID 전 중단 | VERIFIED: 0.160.0 interrupt-before-id, 출력 0바이트, -15 |
| ID 후 중단 | VERIFIED: 0.160.0 interrupt-after-id, ID 원문 보존, -15 |
| 종료 후 drain | 수집기는 EOF까지 읽음. offline late-output / descendant pipe 테스트 통과. 실제 CLI 종료 이후 도착한 최종 출력의 강제 재현은 NOT_VERIFIED |
| 프로세스 그룹 정리 | offline 자식 파이프 테스트 통과. 새 세션으로 이탈한 임의 자식까지 보장하는 OS 프로세스 트리 검증은 NOT_VERIFIED |
| agy 미로그인 | NOT_VERIFIED: 인증 격리 계약 미확인. 실제 계정 로그아웃하지 않음 |
| agy --continue 전역 범위 | NOT_VERIFIED: 기존 대화에 접근하지 않음. 어댑터는 명시 ID 사용 |
| agy 다른 cwd resume | VERIFIED: 호출 cwd를 clone root로 바꿔도 실제 실행 cwd는 원래 worktree. ID/문맥 유지 |
| agy 중간 종료 / 부분 JSON | 중간 종료 VERIFIED: 3초 후 SIGTERM, exit 1, 완성 JSON status ERROR / context canceled / 빈 ID. 잘린 JSON 실측은 NOT_VERIFIED |
| 네트워크 차단 실패 | NOT_VERIFIED: 네트워크 설정 변경하지 않음. Codex turn.failed는 잘못된 모델로 실측 |

## 로컬 파이프라인 및 남은 완료 조건

`verify-pipeline.py`는 두 provider를 사용하는 독립 로컬 smoke 검증이다.
plan → implement(의도적 버그를 드러내는 테스트 커밋) → verify(실패 기록) →
prepare-review(이전 결과 무효화, HEAD SHA 고정) → review(NEEDS_FIX + SHA 검사) →
fix(수정 커밋) → 실제 unittest 재실행을 수행한다.
단계별 세션 ID·종료코드·소요 시간은 `samples/pipeline/local-smoke/stages.json`에 보존한다.
전체 결과는 그 디렉터리의 `summary.json`을 따른다.

**로컬 smoke 결과: VERIFIED.** 모든 agent 프로세스 exit 0, 수정 후 unittest 2개 통과,
로그인 셸 `gh auth status` exit 0. 리뷰 SHA는
`c0f498ddbeb5332fccf9f6d58469dcd65aa0becd`, 수정 SHA는
`25528703476cf69365d2f387076a6c1a8adedf4f`다.

| 단계 | provider / 세션 ID | CLI 종료코드 | 소요 초 |
|---|---|---:|---:|
| plan | Codex / `01a109c7-dc2a-7702-b689-10d8aaf21ea9` | 0 | 19.326 |
| implement | Codex / 동일 | 0 | 20.519 |
| verify | Codex / 동일 | 0 | 24.933 |
| review | agy / `8585a3af-8188-463d-86fd-15c9b6b24dec` | 0 | 43.041 |
| fix | Codex / 기존 Codex 세션 | 0 | 32.918 |

verify 단계는 **테스트 실패를 정확히 기록하는 작업**이므로 agent CLI 성공과 내부
테스트 성공을 혼동하지 않는다. 최종 테스트는 별도 `final-tests/` 프로세스로 확인했다.

§4의 실제 이슈/원격 저장소를 사용하는 전체 예시 및 원격 publish 검증과는 다르다.
초기 실행에서는 원격이 지정되지 않아 로컬 검증만 진행했다. 이후 사용자가 Manicule을
지정하여 아래 GitHub 통합 검증을 추가했다. 실제 Manicule 기능을 구현하는 전체 개발
사이클과는 구별하며, 위 필수 미로그인 실패 계약은 아직 남아 있다.

## 2026-10-05 추가 검증: Manicule / GitHub

원문: `samples/additional/manicule-v2/`. 실행 도구: `verify-additional.py`,
`verify-github.py`, `cleanup-github-test.py`.

- 원본 `/Users/leeseungyun/project/Manicule`의 상태는 전후 clean, HEAD는
  `97129007adb5a7387b7482305b2ee8f4fc61c0aa`로 유지했다. 독립 clone에서만 fetch·worktree·커밋 수행.
- 실제 GitHub 이슈 조회, 원격 fetch, 로그인 셸 gh repo 조회, WRITE 권한 확인.
- agy 로그인 성공 재확인. 잘못된 모델은 **effort 없이도** exit 1 / JSON status ERROR /
  `model ... is not recognized` 오류. 기존 모델+effort 실패와 별도 fixture.
- agy 새 세션 `7da463ba-b47e-4860-bb6c-4dcdd40c3e44`: new 10.135초,
  다른 cwd resume 10.806초, 모두 exit 0. 실제 cwd는 원래 워크트리이며 토큰 기억 유지.
- agy 취소는 exit 1이므로 **exitCode만으로 일반 실패와 취소를 구분할 수 없다**.
  수집기의 termination=timeout과 오류 JSON을 함께 기록한다.
- 첫 중단 실험의 프로세스 그룹 정리에서 PermissionError가 발생해 결과 저장이 누락됐다.
  수집기 수정 후 재실행 원문은 `agy-interrupt-retry-d1039d/`에 보존했다.
  최초 `agy-interrupt/`는 불완전한 수집 자료이며 종료코드를 추정하지 않는다.
- `manicule/` 첫 시도는 로그 폴더/worktree 경로 충돌로 setup 실패. 수정 후 v2에서 성공.

### GitHub 게시 / 정리 결과

[테스트 PR #113](https://github.com/LeeSeungYun1020/Manicule/pull/113)은 문서 fixture 1개만
추가한 draft PR이다. 정확한 리뷰 head/base·clean worktree 검사, 실제 push, 원격 SHA 확인,
PR 생성, 동일 head/base의 열린 PR 재사용을 모두 통과했다.

| 항목 | 결과 |
|---|---|
| 검토/게시 head | `0a422b5164a36bcc88344a572b34347108c20c17` |
| 검토 base | `d3913e5a74ebc041c72f598bd7a5f0fe533b3ef9` |
| 임시 branch | `ai/aiflow-phase0-703e619e` |
| 검증 파일 | `.aiflow-phase0-smoke.md` 1개, 앱 코드 변경 없음 |
| PR / CI | draft 생성·재사용 VERIFIED, Gradle Check SUCCESS |
| 정리 | PR CLOSED, 병합 없음, 임시 원격 branch 삭제 확인 |
| 계정 | 게시 LeeSeungYun1020. 기존 활성 lsy-auto는 변경하지 않음 |

원문은 `github-prepare/`, `github-publish-4c82d7/`, 그 아래 `cleanup/`에 있다.
`github-publish/` 첫 게시 시도는 활성 계정 lsy-auto를 감지하여 **push 전에 중단**했다.
재시도는 이미 저장된 LeeSeungYun1020 인증을 자식 프로세스 환경에만 전달했으며,
토큰을 파일·로그·argv에 기록하지 않았다. 리뷰 요청이나 이슈 변경은 수행하지 않았다.
CI: [Gradle Check 실행](https://github.com/LeeSeungYun1020/Manicule/actions/runs/37267727596).

### 사용자에게 필요한 작업

1. **필수 완료 게이트: agy 미로그인 fixture 확보.** 현재 로그인 상태는 정상이다.
   기존 인증을 건드리지 않으려면 별도 macOS 사용자/VM 등 로그인하지 않은 agy 환경이
   필요하다. 그 환경에 scripts 폴더를 복사한 후 아래 명령을 실행해 출력 디렉터리를 전달하면 된다.

   ```sh
   bash scripts/verify-agy.sh --output /tmp/aiflow-agy-unauthenticated
   ```

   `models/`의 stdout.log, stderr.log, result.json과 `version/`이 필요하다.
   단순 네트워크 실패나 모델 조회 성공을 미로그인 오류로 간주하지 않는다.
   현재 계정에서 강제 로그아웃하거나 인증 파일을 삭제할 필요는 없다.
2. **GitHub 관련 추가 작업은 없음.** 테스트 PR과 원격 브랜치까지 정리했다.
   기존 로컬 Manicule의 main 동기화나 앱 변경은 사용자 작업 범위에 포함하지 않았다.
3. **Codex 설정 시 선택 사항:** 호환성 검증을 마친 앱 번들 CLI 0.160.0 경로를 사용한다.
   PATH의 0.146.0으로 gpt-6-astra를 사용하려면 CLI 업데이트가 필요하지만 이번 검증에서는
   업데이트하지 않았다.

## 구현 및 검증

- 수집기: Python 3 표준 라이브러리. 동시 pipe 소비, stdin close, 실제 CLI 상태,
  timeout/시그널/출력 완료 분리, 새 evidence 경로만 허용.
- `test_phase0.py` + `test_github_guard.py`: 11개 오프라인 테스트 통과.
  정리 권한 오류에도 증거 저장, 정확한 APPROVED 문자열·SHA·clean 상태 검사 포함. CLI 실측과 구분.
- `samples/synthetic/`: 명시적 실패+exit 0, 잘린 JSON, ID 누락, 일반 로그,
  정상 빈 shell 출력 6개. source=synthetic. Phase 04 파서 테스트는 아직 미구현.
- 계획서 §2·§5와 `04-provider-adapters.md`의 agy 모델 목록·실패·성공 판정 계약 수정.
- 참고: [공식 Codex exec 문서](https://learn.chatgpt.com/docs/developer-commands#codex-exec).
  설치 버전의 실제 도움말과 출력이 이번 계약의 최종 기준이다.
