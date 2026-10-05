# Phase 0 CLI verification

이후 검증에는 `../00-overview.md`의 비용 원칙을 적용한다. 모델 없는 검사/fixture 재생을
우선하고, 실제 모델이 필요하면 지원 확인한 GPT-6 Luna 등 저비용 모델과 low effort를 명시한다.
**현재 과거 재현용 도우미 일부는 Astra 고정 또는 Codex 기본 모델 상속을 사용한다.**
추가 실모델 실행 전 01 계획의 모델/effort 인자화 작업을 먼저 완료한다. 아래 명령들은 기존
재현 방법이며 저비용 정책을 충족한 기본값으로 변경됐다는 의미가 아니다.

Python 3 표준 라이브러리, Bash, Git을 사용한다. 사용자 저장소 대신 실행마다 별도
샘플 저장소와 `ai/test` 워크트리를 생성한다. 사용자 인증을 삭제하거나 로그아웃하지 않는다.

```sh
# 도움말/버전/로그인/모델 조회만 수집
bash scripts/verify-codex.sh
bash scripts/verify-agy.sh

# 실제 모델 호출: 네트워크, CLI 세션/로그 저장 권한과 모델 사용량이 필요
bash scripts/verify-codex.sh --run --edge-cases
bash scripts/verify-agy.sh --run --model gemini-3.8-flash-low

# PATH CLI가 오래된 경우, 이미 설치된 다른 바이너리를 명시
bash scripts/verify-codex.sh --run --edge-cases \
  --binary /Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex

# 오프라인 수집기/계약 테스트
python3 -m unittest discover -s scripts -p 'test_*.py' -v

# 두 provider를 연결한 로컬 5단계 smoke (원격 §4 예시와 별도)
python3 scripts/verify-pipeline.py \
  --codex /Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex \
  --agy-model gemini-3.8-flash-low --output /tmp/aiflow-pipeline-new

# 실제 프로젝트는 독립 clone에서 조회·프로토콜만 추가 검증
python3 scripts/verify-additional.py --source /Users/leeseungyun/project/Manicule \
  --output /tmp/aiflow-additional-new
# 수집기 중단 후에는 같은 명령에 --resume을 붙인다. 원문은 덮어쓰지 않는다.

# 로컬 테스트 문서 커밋, Codex SHA 리뷰, push dry-run
python3 scripts/verify-github.py --evidence /tmp/aiflow-additional-new
# 아래는 실제 GitHub 쓰기다. 이 저장소에 게시하도록 허용받은 경우에만 실행한다.
python3 scripts/verify-github.py --evidence /tmp/aiflow-additional-new --publish
# 실제 성공 결과 디렉터리를 지정: PR/branch/SHA를 재검사한 뒤 테스트 자료만 정리
python3 scripts/cleanup-github-test.py /tmp/aiflow-additional-new/github-publish
```

`--output <새 디렉터리>`로 결과 위치를 지정한다. 기존 경로는 덮어쓰지 않는다.
`--timeout <초>` 기본값은 120초다. Codex는 `--model`을 생략하면 사용자 기본 모델을
사용하고, agy는 `models` 실측 목록에서 고른 모델을 반드시 지정한다.

각 case의 `stdout.log`, `stderr.log`는 가공하지 않은 원문이다. `stdin.txt`는 실제
입력, `result.json`은 실제 argv/cwd/버전/UTC 시각/종료코드/수신 chunk 시각을 담는다.
`exitCode`는 Python Popen의 실제 returncode로, 음수는 시그널 종료다.
시작 실패는 null이며 timeout을 임의의 CLI 종료코드 124로 바꾸지 않는다.
`termination`이나 불완전한 출력이 있으면 종료코드 0이어도 성공으로 처리하지 않는다.
수신 시각은 CLI가 출력한 시각과 같다고 가정하지 않는다.

새 세션은 FILES.md 작성 후 임의 토큰을 대화에서만 기억하게 하고, resume에는 토큰을
재전달하지 않는다. 동일 ID, 이전 토큰 응답, 파일 수정, 최종 성공 신호와 실제 종료코드를
함께 검사한다. `summary.json`의 VERIFIED는 이 좁은 new/resume 계약에만 해당한다.
전체 Phase 0 완료 여부와 수동 실험 항목은 `phase0-result.md`를 따른다.

GitHub 추가 검증은 Manicule 및 게시 계정 `LeeSeungYun1020`을 대상으로 한 전용 스크립트다.
저장된 해당 계정 인증을 자식 프로세스 메모리에서만 사용하며 공유 gh 활성 계정을 바꾸지 않는다.
실제 게시 전에는 APPROVED 첫 줄, head/base SHA, 원격 base, clean 상태를 다시 검사한다.
정리는 이 도구가 만든 draft PR의 SHA/branch/title을 확인한 뒤 수행한다.
권한 오류 등으로 프로세스 정리를 확인할 수 없으면 collectorError로 기록하며 성공으로 판정하지 않는다.

로그에는 로컬 경로와 세션 ID가 포함된다. `repo/`, `worktree/`, 격리 인증 홈은 로컬에
보존하고 Git 추적에서 제외한다. 원문을 공유하기 전에 직접 확인한다.
synthetic 자료는 `samples/synthetic/` 아래에서만 관리하며 실측과 혼동하지 않는다.
`samples/` 전체는 로컬 검증 자료로 Git에서 제외한다. 이 문서와 결과 보고서의 samples 경로는
기존 실행 환경의 증거 위치이며 새 clone에 포함되지 않는다. Phase 04에서는 필요한 자료만
검토해 테스트 리소스로 별도 추가하고, 로컬 원문은 변경하지 않는다.

공식 Codex 호출 참고: https://learn.chatgpt.com/docs/developer-commands#codex-exec
실제 지원 플래그의 기준은 각 실행에 보존한 설치 바이너리의 도움말이다.
