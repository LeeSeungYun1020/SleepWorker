# Phase 1 구현 결과

구현 범위: `02-create-project.md`, `03-data-model.md`, `04-provider-adapters.md`.
검증일: 2026-10-05, macOS / JBR 21.0.11.

## 구현

- Kotlin 2.3.21 / Compose Multiplatform 1.10.3 / Gradle 9.3.0, 버전 카탈로그,
  `shared` JVM 타깃과 `desktopApp`, 편집·실행·히스토리·설정 네비게이션 뼈대.
- 프로세스 동시 출력 수집·stdin close·실제 exit 분리·자식 종료·제한된 정리 대기,
  정리 실패 뒤 추가 실행 차단, 임시 스크립트·알림·PATH 탐지·OS 저장소 잠금.
- YAML 표현과 원문 보존, 명시적 그래프 검증, 세션 생성 경로 고정점 분석,
  초안·불변 버전·복원 이력, 원자적 파일 교체·UUID 경계·공유 쓰기 잠금.
- Codex/agy/shell 커맨드와 시도별 파서, 인증·모델 조회, 실측 계약 및 설정 저장.
  프리플라이트 계약 검사와 shell 파일 생성은 엔진/실행 화면에서 연결할 API다.

## 검증 결과

- `./gradlew build :desktopApp:packageDmg`: **성공**.
- 최종 테스트 **33개 성공**, 실패·오류·건너뜀 0.
  종료 후 출력·별도 JVM 잠금·세션 거부 회귀 검증을 포함한다.
- 원문 fixture: Codex new/resume/잘못된 모델/401, agy new/resume/다른 cwd 재개/
  잘못된 모델/취소/TSV, synthetic 6개. `source` 및 관측된 `exitCode`를 사용했다.
- 로컬 프로세스: echo, stdin EOF, 실제 exit 7, 종료 직전 출력, 양쪽 pipe 각 10,000줄,
  자식 종료, 주입된 권한 거부·timeout, 잠금 중복 차단·해제 후 재획득.
  별도 JVM 강제 종료 뒤 OS 잠금 해제와 종료 후 지연 출력 검증도 포함한다.
- 저장 경계: 실패한 교체의 기존 초안/버전 보존, 버전 충돌, 심볼릭 링크 거부,
  두 store의 쓰기 직렬화, WARNING 확인, 잠금 해제 후 접근 차단.
- 모델 호출 **0회**, 사용자 인증 변경·외부 게시 **없음**.
- `:desktopApp:run` 프로세스 실행 및 OS 앱 목록의 `MainKt` 확인.
  Mac 잠금 해제 후 패키징된 `aiflow.app`의 화면 표시를 확인했다.
  편집 → 실행 → 히스토리 → 설정 → 편집 클릭과 콘텐츠 제목 전환,
  선택 버튼 표시를 접근성 트리와 화면으로 확인했다.
- DMG: `desktopApp/build/compose/binaries/main/dmg/aiflow-1.0.0.dmg` (약 75 MB).
  macOS 패키지 버전 제약으로 major 1을 사용했으며 배포 서명/공증은 Phase 5 범위다.

## 다음 단계와 유지한 제한

- Phase 2 실행 엔진은 아직 구현하지 않았다. 정상 출력 drain 제한, 런/시도 기록,
  completion·전이·retry·복구를 플랫폼/파서 API에 연결해야 한다.
- agy 미로그인 실측은 기존 합의대로 Phase 3 이전까지 이월한다.
- GPT-6 Luna 및 추가 모델/effort 조합은 지원을 추정하지 않으며 계약 검사가 차단한다.
- 명시적 terminal 세션 거부는 SESSION_INVALID로 분류하고 요청 ID를 보존한다.
  이 분기는 synthetic 응답으로 검증하며 실제 CLI 세션 거부 문구 계약은 미실측이다.
  알 수 없는 오류는 PROVIDER/PROTOCOL로 보존하고 새 세션으로 바꾸지 않는다.
- 프로세스 트리는 관측된 OS descendants를 대상으로 검증했다. 발견 전에 분리된 임의
  daemon의 종료 보장이나 강제 앱 종료에 대한 저장 내구성까지 검증한 것은 아니다.

의존성 선정 참고: [Compose 1.10.3](https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html),
[kaml 릴리스](https://github.com/charleskorn/kaml/releases).
