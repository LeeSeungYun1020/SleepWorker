# Phase 5 결과 — 2026-10-09 (Asia/Seoul)

히스토리·설정·알림·데스크톱 마무리와 DMG 패키징을 구현했다.
최종 `./gradlew build :desktopApp:packageDmg` 성공. Kotlin 테스트 **153개 통과**
(최초 설치 검증 시점 151개, 후속 CLI 정책 회귀 검증 추가).
빌드/테스트는 모델 호출 없이 수행했다. 설치본 CLI 검증만 Luna medium **3회** 호출했다.

## 구현 범위

- 독립 히스토리 화면: 최신순 런 목록, 정확히 기록된 시작/종료 정보, 과거 스냅샷,
  방문·시도·수동 재시도·사용자 판정·전이 우선순위와 검사 결과를 읽기 전용으로 표시.
  파일 목록/검색은 방문별 필터를 지원하며 선택한 원문만 지연 로드한다(512 KB 미리보기).
  바이너리/버전/계약·요청 모델/effort·provider usage를 제공하고 미관측 값/가격은 추정하지 않는다.
- INTERRUPTED 상세: 직전 상태·마지막 기록·사유·발견 시각 및 부분 로그/외부 프로세스
  불확실성을 표시한다. 자동 재개 기능 없이 새 실행을 안내한다.
- 원본 버전 열람·초안 복원·새 버전 저장. 버전 파일이 없으면 런 스냅샷을 명시적으로
  새 워크플로로 가져온다. 세션 ID 및 기록된 요청 값의 noninteractive resume 명령 복사.
- 히스토리 전용 워크트리 조회/수동 삭제 확인. main/locked/변경된 항목 보호, 삭제 직전
  재검사, 브랜치 유지. terminal 런 삭제는 해당 디렉터리만 삭제하며 버전 파일은 유지한다.
  읽기/삭제는 저장소 소유권과 경로 검사를 유지하고 symlink 탈출을 거부한다.
- 독립 설정 화면: PATH·번들·NVM CLI 후보의 존재/버전 확인과 명시 선택, 사용자 지정
  경로 검사, 과거 검사/계약 기록, Codex 모델 후보 추가·삭제·순서 변경, agy TSV 갱신과
  경로/버전/시각 캐시. 갱신 실패는 과거 캐시를 유지하며 오류를 표시한다.
- 즉시 원자적 설정 저장, 새 워크플로용 worktree root, 방문별 로그 상한, 4종 알림
  on/off·선택. 설정 갱신과 창 위치 저장을 직렬화하여 서로 덮어쓰지 않는다.
  프리플라이트 이후 실행 관련 설정 변경을 시작 시 재확인한다.
- CLI 실측 기록은 허용 목록이 아닌 근거로 사용한다. 미실측 버전·모델·effort 및
  목록에 없는 모델은 경고 후 요청 값 그대로 실행한다. 미실측 버전의 계약 ID는 null로
  기록하며 기존 버전의 측정 근거를 빌려 쓰지 않는다. 인증 불명·로그아웃·워크플로 오류와
  해당 버전에서 명확히 지원하지 않는 요청은 계속 차단한다. 모델 갱신과 수동 resume도
  실측 여부만으로 차단하지 않는다.
- osascript 알림의 title/body/subtitle 이스케이프와 COMPLETED/FAILED/AWAITING_USER/PAUSED
  분기. 런 종료 시 observer 취소가 완료 알림을 취소하지 않도록 별도 앱 scope에서 전달.
- 창 크기·위치 저장(화면 밖 좌표는 기본 위치로 복귀), File/Run/Help 메뉴, bundled plan,
  graph 아이콘 `.icns`, `dev.local.aiflow`, macOS DMG.
  Cmd+Q/창 닫기에서 초안 저장·버리기·취소 및 미완료 실행 확인을 거치고, 중단 기록과
  프로세스 정리 후 종료한다. 예기치 않은 오류 다이얼로그와 `~/Library/Logs/aiflow/app.log`.

## 검증

**오프라인/로컬 테스트:** 기존 엔진·에디터·프리플라이트·복구 검증에 Phase 5 테스트 15개를
추가했다. 설정의 이전 형식/미지 필드·원자적 저장·동시 갱신, 모델 갱신 실패/TSV argv,
PATH/커스텀 버전 분리, prerelease와 안정 버전의 근거 분리, 알림 4종 선택/인용, 지연된 완료 알림 1회 전달,
파일 지연 로드/상한/버전 보존/활성 런 보호/symlink 거부, resume 인용/미실측 요청 허용,
실제 Git worktree의 main/dirty/locked 보호·수동 삭제·브랜치 보존을 확인했다.
후속 정책 검증에는 미실측 Codex/agy 버전·모델·effort의 new→resume 완주,
요청 argv 유지·계약 ID null·인증 확인 유지·명확한 UNSUPPORTED 구분을 포함한다.
이는 fake CLI 테스트이며 새 버전의 실제 provider 호환성을 실측한 기록은 아니다.

**DMG 설치본:** DMG를 read-only로 마운트하고 `/tmp/aiflow-phase5-installed/aiflow.app`에
복사했다. 호출 환경 PATH를 `/usr/bin:/bin`으로 제한하고 Launch Services(`open`)로 실행했다.
Finder 더블클릭 자체는 사용하지 않았으나 터미널 자식으로 앱 바이너리를 직접 실행하지 않았다.
CUA로 설정 자동 탐지/선택·버전 확인·agy TSV 캐시, 로컬 셸 2단계 완주, 히스토리 로그
지연 열람·메타데이터·세션/명령 복사, 메뉴 단축키, 초안 종료 저장을 확인했다.

**실제 CLI:** Codex 0.160.0 네이티브 바이너리·명시 `gpt-6-luna`/`medium`을 사용했다.
기존 계약에 low 검증이 없어 medium을 유지했으며 모델을 암묵 변경하지 않았다.
설치 앱의 new→resume 두 단계가 동일 세션·각 exit 0으로 약 17초에 COMPLETED가 되었고,
요청한 파일 생성/추가 및 completion 검사도 통과했다. 이어 기록된 resume argv를 로컬
프로세스에서 재현하여 약 5초/exit 0/동일 ID/`TERMINAL_RESUME_OK`를 확인했다.
Terminal 앱은 CUA 도구에서 접근이 제한되어 터미널 UI를 통한 실행은 하지 않았다.
provider usage는 [요약 JSON](fixtures/phase5/acceptance-summary.json)에 보존했다.

**종료·복원:** 15초 로컬 셸 단계 중 pause 요청→현재 단계 완료→다음 단계 진입 없이 PAUSED.
File 새 워크플로 메뉴로 만든 미완성 초안과 PAUSED 런을 Cmd+Q 저장 종료했다.
런은 이전 상태 PAUSED·app_shutdown 사유의 INTERRUPTED로 저장되었고 재시작 후
추가 방문/프로세스 실행 없이 히스토리에 표시됐다. 미완성 초안 재열기, 과거 버전 복원 후
새 버전 생성 및 원본 버전/기존 런 유지도 설치본에서 확인했다. 마지막 히스토리 문구/파일
검색 표시 수정은 재패키징·설치 후 확인했으며 실모델 흐름은 반복하지 않았다.

## 산출물과 제한

DMG: `desktopApp/build/compose/binaries/main/dmg/aiflow-1.0.0.dmg` (약 79 MB).
최종 빌드/테스트와 DMG 해시: [정책 변경 후 검증](fixtures/phase5/post-policy-verification.json).
최초 설치본의 해시와 실행 요약: [설치 검증 fixture](fixtures/phase5/acceptance-summary.json).
최종 정책 변경 후 패키지를 다시 빌드했으며 설치본의 실모델 검증은 반복하지 않았다.
설치·사용법·계획과의 차이는 [README](../README.md)에 정리했다.

- agy는 최초 설치 검사 도중 1.3.1에서 **1.3.2**로 갱신됐다. 1.3.1의 모델 목록 조회는
  확인했지만 당시 1.3.2는 계약 없음으로 프리플라이트가 차단했다(모델 호출 0회).
  현재 구현은 미실측 버전만을 이유로 차단하지 않는다. 1.3.2의 실제 new/resume은
  여전히 미실측이며 호환 계약을 임의 확대하지 않았다.
- 알림 4종의 이벤트 선택·인용·전달 코드를 테스트했다. 후속 사용자 제공
  [알림 센터 캡처](fixtures/phase5/notifications-completed-paused.png)에서 설치본의
  `Phase 5 installed Codex new resume` 실행 완료와 `Phase 5 shutdown and pause`
  일시정지됨 알림의 실제 표시를 확인했다. 실패/사용자 확인 필요의 실제 표시는 미확인이다.
  알림 센터 직접 조회는 CUA에서 위젯 창만 노출되어 목록을 독립적으로 확인하지 못했다.
  표시 여부는 OS 알림/Focus 설정에 따르며 클릭으로 앱을 활성화하는 기능은 범위 밖이다.
- Developer ID 서명/notarization을 하지 않은 본인 사용용 패키지다. Gatekeeper의
  최초 차단/허용 UI는 실측하지 않았으며 현재 Apple 안내를 README에 연결했다.
- 설치 검증은 개인 앱/설정에 덮어쓰지 않는 임시 설치본/격리 저장소로 수행했다.
  기존 버전 기록의 run 시작 시각은 없는 경우 미관측으로 표시한다.
