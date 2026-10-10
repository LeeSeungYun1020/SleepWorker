# Phase 6 — 디자인 개선 결과

2026-10-10 · 계획: [09-design-improvement.md](../09-design-improvement.md)

## 검토 결과

선택 요소를 행동 버튼과 구분하고, 식별자 반영과 패널 위계를 정리하는 방향을 적용했다.
절대적 버튼 개수 규칙, 시간만 사용하는 undo 병합, 전이 문자열 지연 저장은 계획 6절의 기준으로 조정했다.
참고: [M3 buttons](https://m3.material.io/components/all-buttons),
[Compose Desktop APIs](https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html).

## 변경

- 공통 SelectField, ComboField, SegmentedChoice, CommitTextField, SectionCard,
  ExpandableSection, SelectionItem, ToolIcon, EmptyState, DetailRow, StatusBadge 추가.
- 시스템/라이트/다크 설정과 중립적인 surface 토큰, 상태 색, 데스크톱 typography/shape 적용.
  기존 설정 파일은 themeMode가 없어도 SYSTEM으로 읽힌다.
- 왼쪽 NavigationRail과 공용 저장소 선택기. 기존 초안 저장/버리기/취소 보호 유지.
- 에디터 주 행동은 저장 후 실행, 저장은 tonal. 나머지 파일 작업은 overflow로 이동.
  그래프/정의/일반 탐색은 enum과 SecondaryTabRow 사용.
- 식별자 Enter/포커스 이탈 반영, 중복 실시간 검증, Esc 되돌리기. 이름 변경 버튼 제거.
  같은 필드의 연속 입력만 undo 병합. 전이 즉시 수정, otherwise 이동 후 선택 유지.
- 모델 입력과 후보 목록을 콤보로 통합. 단계 종류/세션 모드를 segmented choice로 표현.
  초안/버전/노드/이슈/전이/기록 파일은 list/card로 표현.
- 단계 속성 및 일반/정의/설정 화면 섹션화. 셸 강조 색과 그래프 색을 테마에 연결.
  그래프 도구 모음은 floating Surface + tooltip 아이콘. 노드 접근성 설명 추가.
- 실행 대상 접기, stdout/stderr 및 phase 단일 선택, 로그 pane 톤 적용.
- 히스토리는 넓은 창에서 목록/상세/파일 3열, 좁은 창에서는 세 화면 전환.
  시도 메타데이터를 key-value 행으로 표시하고 미관측 값은 계속 알 수 없음으로 남긴다.
- CLI 후보 radio 목록, 모델 목록 조작 아이콘, Enter 모델 추가, 알림 이벤트 행 전체 선택.
  저장소/YAML/CLI/기본 루트 native picker 제공.
- Edit 메뉴와 ⌘Z/⇧⌘Z/⌘F, Run ⌘R/⇧⌘P 추가. 메뉴 활성 상태는 canUndo/canRedo와 화면·입력 포커스를 직접 관찰한다.
  삭제/강제 중지에 error 색과 구체적인 확인 동사를 적용했다.

## 검증

- `./gradlew build`: 기존 전체 테스트와 새 테스트 156건 통과 (실패/오류/skip 0).
- `./gradlew :desktopApp:createDistributable`: 앱 생성 성공.
- 새 회귀 검사: 필드별 undo 병합 및 undo 이후 병합 경계, otherwise 이동 후 연결 선택 유지,
  중복 otherwise 거부 시 초안 유지, 기존 설정 기본 테마 및 테마 저장/CLI 경로 보존.
- 실제 빌드한 앱을 별도의 설정 파일과 `/tmp/aiflow-design-review/repo`로 실행.
  사용자 설정 파일이나 실제 작업 저장소에서 워크플로를 실행하지 않았다.
- 1280×850 / 790×742 창의 라이트·다크 화면을 스크린샷으로 시각 검사.
  편집기 패널/일반 카드, 실행 도구, 설정, 히스토리 list-detail 및 넓은 3열을 확인했다.
  좁은 창 재실행의 초기 폭은 기존 geometry 하한에 따라 800이며 790 검사는 resize로 수행했다.
- 실제 ID `first` → `entry` Enter 반영, `second` 중복 거부, Esc 복구를 확인했다.
- 실제 ⌘O 초안 열기, ⌘F 노드 목록 열기, ⌘Z/⇧⌘Z 일반 필드 되돌리기/재적용, ⌘S 버전 저장 확인.
- 선형 셸 3단계 저장 → preflight → 완료 → 과거 시도 메타데이터 → command.txt 지연 로드 확인.
  runId `20261009-153653-9b5fb88af74f4f7f`, 세 방문 모두 exit 0.
  에이전트 모델 호출이나 CLI 인증 변경은 수행하지 않았다.

## 검증 한계

OS 스크린리더 전체 탐색, 모든 키보드 조합의 end-to-end 검사, 확대 글꼴/모든 창 크기는 인증하지 않았다.
사용한 파일/로그 원문 및 CLI 진단에는 기술 식별자가 그대로 표시된다.
Material Icons Extended의 용량 절감용 벡터 개별 추출, 아이콘 전부의 디자인 통일은 별도 최적화 대상이다.
DMG 재배포/서명/설치는 이번 UI 작업의 검증 대상이 아니다.

## PR #6 리뷰 반영

- P1: 워크플로 undo/redo를 편집 화면·텍스트 미포커스 조건으로 제한하고 native 메뉴의 전역 단축키 등록을 제거했다. 캔버스 선택 전 보류 중인 식별자를 검증·반영하며, 중복 이름은 오류를 표시하고 선택을 막는다.
- P2: secondary/tertiary/error와 관련 on/container 토큰을 명시했다. 확인 버튼 라벨·위험 여부를 데이터로 전달한다. 파일 선택 예외는 인라인 오류로 표시하고 cancellation은 다시 전파한다. 버전 선택은 표시 문자열 대신 객체를 전달한다. 메뉴는 실제 StateFlow를 구독한다.
- P3: 상태별 배지 아이콘, 펼칠 수 있는 검사 도움말, 변경이 있을 때만 Esc 소비, 설정 목록 배경, 히스토리 미선택 안내, 최대 방문 횟수 필드별 undo 병합을 반영했다.
- Material Icons Extended 축소는 보류했다. 동작 결함이 없는 후속 최적화이며, 개별 벡터 추출·축소기 도입은 패키징 변경과 실제 용량 측정이 필요하다.
- `./gradlew build :desktopApp:createDistributable` 성공. 전체 테스트 162건, 실패/오류/skip 0. 입력 포커스 수명, 보류 식별자 검증, 색상 대비, 파일 선택 예외/취소, undo 컨텍스트 및 숫자 필드 병합 회귀 검사를 포함한다.
- 별도 설정/fixture에서 실제 캔버스 ⌘Z/⇧⌘Z로 추가 노드 제거/복구, 설정 텍스트 ⌘Z에서 텍스트만 복구되고 화면·워크플로 유지됨을 확인했다. ID 수정 후 다른 노드 직접 선택 시 반영과 중복 ID의 선택 차단도 확인했다. 검증용 초안 변경은 저장하지 않고 버렸다.

## 실행 확인 후 추가 보완

- SegmentedChoice는 가장 긴 라벨과 선택 아이콘·여백을 기준으로 동일한 버튼 폭을 확보하고 라벨을 한 줄로 표시한다. 실행 화면의 완료 확인/전이 조건과 좁은 창 방문 목록/선택 방문 로그의 줄바꿈·잘림을 방지했다. 기존 실행 로그 행의 가로 스크롤을 유지한다.
- 단계 ID를 제목과 같은 기본 정보 카드에 배치하여 다른 속성과 좌우 폭을 통일했다. ScriptEditor의 중복 모드 설명을 제거했다.
- 첫 비공백 `!` 하나를 제거하는 기존 shellScript 경로를 확인했다. 추가 통합 테스트는 단일·여러 줄 스크립트를 실제 셸로 실행하고 결과와 script.txt 원문에 접두사가 제거되는지 검증한다. 이후 명령 본문의 `!`와 나머지 공백은 유지한다.
- 전체 테스트 163건 통과(실패/오류/skip 0), `./gradlew build :desktopApp:createDistributable` 성공. 실제 앱의 넓은 창에서 필드 폭과 스크립트 배치를, 약 812×742 좁은 창에서 긴 선택 라벨과 체크 아이콘을 시각 검증했다.
