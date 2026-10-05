# 01 — CLI 수동 검증 (Phase 0)

선행: 없음 | 다음: `02-create-project.md`

> 2026-10-05 진행 결과: `scripts/phase0-result.md` 참조. 두 provider의
> 워크트리 new/ID/resume은 실측 완료. agy 모델 목록 JSON 옵션은 미지원이며
> 실제 오류 포맷을 계획서와 04에 반영했다. Manicule에서 원격 push·draft PR 재사용·CI 및
> 정리까지 추가 검증했다. agy 미로그인 오류 실측이 남아 전체 완료는 아님.

## 목표

Codex CLI와 Antigravity CLI(`agy`)가 **헤드리스 모드에서 새 세션 생성 → 세션 ID 획득 → resume** 흐름이 실제로 동작하는지, 그리고 출력 포맷·종료코드·에러 포맷이 계획서 §5의 가정과 일치하는지 확인한다. 어긋나는 부분은 기록하여 04(어댑터)에 반영한다.

## 선행 조건

- macOS에 `codex`, `agy`, `git`, `gh` 설치 및 로그인 완료
- 검증용 Git 저장소 하나(작은 샘플 프로젝트, GitHub 원격 연결)

## 작업 항목

### 0. 검증 계약과 결과 분류
- [ ] 실제 모델 호출 전 `00-overview.md`의 검증 비용 원칙 적용. 셸/fixture로 충분하면 호출하지 않고, 필요한 프로토콜 검증은 지원 확인한 GPT-6 Luna 등 저비용 모델 + low effort부터 명시 선택
- [ ] 기존 검증 도우미의 고정 `gpt-6-astra` 및 Codex 기본 모델 상속을 후속 실모델 재검증 전에 명시 모델/effort 인자로 교체(`--codex-model`, `--agy-model`, `--effort` 등). 이전 fixture의 모델/결과는 변경하지 않음
- [ ] 이 문서의 명령·플래그·이벤트 이름은 검증 후보다. 설치된 바이너리의 버전·도움말·실측 결과로 확정하고, 문서와 다르면 04 및 계획서 §2·§5를 함께 갱신
- [ ] provider별 계약표: 바이너리 경로/버전, new/resume의 실제 argv·stdin·cwd, 모델/effort 지원 범위, 세션 ID 필드·발생 시점, 스트리밍/종료 후 일괄 출력 여부, 최종 성공/실패 신호, 빈 출력 허용 여부, 미지원 기능 처리
- [ ] 각 실험은 stdout·stderr·CLI 자체 종료코드·프로세스 시작/종료 시각을 따로 기록. 아래 `tee` 예시는 표시용이며 파이프라인 마지막 명령의 종료코드를 CLI 종료코드로 기록하지 않음
- [ ] 결과를 VERIFIED / UNSUPPORTED / NOT_VERIFIED로 구분. 성공을 가정해 기록하지 않음. new·ID 추출·resume 필수 계약이 미검증이면 해당 어댑터 완료로 간주하지 않고 원인과 필요한 외부 준비를 기록

### 1. 환경 확인
- [ ] `codex --version`, `agy --version` 기록
- [ ] `codex login status` 종료코드·출력 확인 (로그인/미로그인 두 상태 모두)
- [x] `agy models`의 TSV `<slug>\t<label>` 구조 기록. 1.2.16에서 `models --output-format json`은 UNSUPPORTED
- [ ] 미로그인 상태에서 `agy models` 실행 시 종료코드·stderr 포맷 기록
- [ ] GUI 앱이 PATH를 상속하지 않는 문제 확인: `/bin/zsh -lc 'command -v codex; command -v agy'` 결과 기록

### 2. Codex 검증 스크립트 `scripts/verify-codex.sh`
- [ ] 새 세션: 프롬프트를 stdin으로 전달하고 **stdin을 닫은** 상태로 실행
  ```
  printf '%s' "현재 디렉토리의 파일 목록을 FILES.md에 작성하라." | \
    codex exec --json -C "$REPO" -m "$MODEL" \
      -c model_reasoning_effort=low \
      -c sandbox_mode=danger-full-access \
      -c approval_policy=never - | tee codex-new.jsonl
  ```
- [ ] JSONL에서 `thread.started` 이벤트의 `thread_id` 추출 (`jq`)
- [ ] 최종 메시지 이벤트 종류와 위치 확인 (`turn.completed` 등)
- [ ] resume: `codex exec --json -C "$REPO" -c ... resume "$THREAD_ID" -` 로 "FILES.md에 한 줄 추가하라" 실행 → 같은 세션 문맥 유지 확인
- [ ] 워크트리 cwd에서 새 세션 생성 후 resume이 같은 워크트리에서 동작하는지 확인
- [ ] 실패 케이스: 존재하지 않는 모델 슬러그 → 종료코드·stderr 기록
- [ ] 실패 케이스: `turn.failed` 이벤트가 나오는 상황(네트워크 차단 등) 재현 가능하면 포맷 기록
- [ ] `resume` 서브커맨드에 `--sandbox` 플래그를 주면 거부되는지 확인 (→ `-c sandbox_mode=`로 통일 근거)
- [ ] stdin을 닫지 않으면 무한 대기하는지 확인
- [ ] 프로세스 종료 직전/종료 후 파이프에 남은 출력에서 최종 이벤트·세션 ID가 누락되지 않는지 확인. 세션 ID 획득 전·후에 각각 중단하여 출력/종료 결과 기록

### 3. Antigravity 검증 스크립트 `scripts/verify-agy.sh`
- [ ] 새 세션:
  ```
  (cd "$WORKTREE" && agy -p "현재 디렉토리의 파일 목록을 FILES.md에 작성하라." \
     --output-format json --model "$MODEL" --effort low \
     --dangerously-skip-permissions) | tee agy-new.json
  ```
- [ ] JSON 엔벨로프에서 `conversation_id`, `response` 추출 위치 확인
- [ ] resume: `agy -p "..." --conversation "$CONV_ID" ...` 문맥 유지 확인
- [ ] `--continue`가 전역 최근 대화를 가리키는지 확인 (→ 항상 `--conversation` 명시 근거)
- [x] 실패 케이스: 잘못된 모델 슬러그 → exit 1, stdout JSON `status: ERROR`/`error`, 일반 stderr 실측. effort 유무 두 경우 모두 fixture 보존; 3/AGY_ERROR를 가정하지 않음
- [ ] 비TTY(파이프) 환경에서 stdout이 비어 있지 않은지 확인 (1.0.x 이슈 재발 여부)
- [ ] 워크트리 cwd에서 생성한 세션을 다른 cwd에서 resume 시 어떤 일이 생기는지 기록
- [ ] 전체 JSON의 완성 시점과 세션 ID 획득 시점 확인. 빈 stdout·부분 JSON·중간 종료가 실제로 관찰되면 원문 보존하고 성공 결과와 구별

### 4. 워크트리·셸 단계 검증
- [ ] `git worktree add ../<repo>.worktrees/impl-wt -b ai/test main` 후 위 두 CLI가 그 cwd에서 정상 동작
- [ ] `/bin/zsh -lc 'git log --oneline -5'` 가 로그인 셸 PATH로 동작하고 `.zshrc` alias는 적용되지 않음을 확인
- [ ] 여러 줄 스크립트를 임시 파일로 저장 후 `/bin/zsh -l <file>` 실행 확인
- [ ] 프로세스 트리 kill 방법 확인: `kill -- -<pgid>` 또는 `pkill -P` (Java `ProcessHandle.descendants()` 대응)

### 5. 5단계 수동 파이프라인
- [ ] 두 스크립트를 조합해 계획서 §4의 agent 단계(plan→implement→verify→review→fix)를 수동 검증. review 전에 prepare-review 셸 단계도 수행하고, implement/fix는 변경을 커밋한 뒤 비교하도록 예시와 동일하게 진행
- [ ] 각 단계의 세션 ID·종료코드·소요 시간을 `scripts/phase0-result.md`에 기록

### 6. 회귀 테스트용 출력 자료
- [ ] `scripts/samples/<provider>/<case>/`에 stdout.log, stderr.log, result.json(실제 종료코드·버전·명령·시점) 보존. 04의 `commonTest/resources/samples/`로 가져가 파서 테스트에 사용
- [ ] 실측: new/resume 성공, 미로그인, 잘못된 모델, 중간 종료, 늦게 도착하는 최종 출력. 재현 불가한 케이스는 NOT_VERIFIED로 표시
- [ ] 실측 원문과 별도로 synthetic 케이스를 명시해 종료코드 0 + 명시적 실패, 잘린 JSON, 세션 ID 누락, 알려지지 않은 일반 로그, 정상적인 빈 shell 출력을 시험. 가공한 자료를 CLI 실측으로 표시하지 않음

## 산출물

- `scripts/verify-codex.sh`, `scripts/verify-agy.sh`
- `scripts/phase0-result.md`: 버전, 실제 플래그, 출력 샘플(JSONL/JSON 원문 1건씩), 에러 포맷, 계획서와 다른 점 목록
- `scripts/samples/` 및 provider별 VERIFIED/UNSUPPORTED/NOT_VERIFIED 계약표

## 완료 기준

- [ ] 두 CLI 모두 new → ID 추출 → resume 이 스크립트로 재현됨
- [ ] 워크트리 cwd에서 동작 확인
- [ ] 미로그인·잘못된 모델 두 실패 케이스의 종료코드와 stderr 포맷이 문서화됨
- [ ] 계획서 §5 표와 다른 점이 `phase0-result.md`에 정리됨 (없으면 "차이 없음" 명시)
- [ ] 필수 new/resume 계약은 실측 VERIFIED이고, 원문 fixture와 CLI 자체 종료코드가 확보됨. 실제 파서 회귀 테스트 통과는 04에서 확인

## 주의

- 실제 모델 슬러그는 설치 CLI·계정과 `agy models`/Codex 지원 계약으로 확인한 값으로 치환할 것. 계획서의 `gpt-6-luna`는 저비용 우선 후보이며 아직 이 프로젝트에서 실측하지 않았다. `gemini-3.8-flash-low`도 요청 effort와 버전을 함께 확인한다.
- 쓰기 권한을 전면 허용하므로 검증은 반드시 샘플 저장소에서만 수행.
