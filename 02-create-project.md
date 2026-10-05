# 02 — KMP 프로젝트 생성 (Phase 1a)

선행: `01-verify-cli.md` | 다음: `03-data-model.md`, `04-provider-adapters.md`

진입 가능: 01의 핵심 실행 계약과 보존 fixture 확보 완료. agy 미로그인 오류 실측은
00의 합의대로 06까지 이월하며 프로젝트 생성·플랫폼·모델 구현의 선행 조건으로 요구하지 않는다.

## 목표

계획서 §7 구조대로 Kotlin Multiplatform + Compose Multiplatform 프로젝트를 생성하고, `shared`(commonMain/jvmMain) + `desktopApp` 구성으로 빈 창이 뜨는 앱을 빌드한다. 플랫폼 의존 인터페이스(`ProcessExecutor` 등)의 뼈대를 먼저 잡는다.

## 작업 항목

### 1. Gradle 설정
- [ ] `settings.gradle.kts`: `shared`, `desktopApp` 모듈 포함, Gradle 버전 카탈로그(`gradle/libs.versions.toml`) 사용
- [ ] 버전 카탈로그에 추가: Kotlin, Compose Multiplatform, kotlinx-coroutines, kotlinx-serialization, kaml(YAML), okio, kotlinx-datetime, kotlin-test
- [ ] `shared/build.gradle.kts`: `kotlin("multiplatform")`, `org.jetbrains.compose`, `kotlin("plugin.serialization")`, 타깃 `jvm()` 하나
  - `commonMain` 의존: compose.runtime/foundation/material3/ui, coroutines-core, serialization-json, kaml, okio, datetime
  - `jvmMain` 의존: compose.desktop.currentOs, coroutines-swing
  - `commonTest`: kotlin-test, coroutines-test
- [ ] `desktopApp/build.gradle.kts`: `kotlin("jvm")` + compose, `compose.desktop.application { mainClass = "MainKt"; nativeDistributions { targetFormats(Dmg); packageName = "aiflow" } }`

### 2. 디렉토리 뼈대
```
shared/src/commonMain/kotlin/aiflow/
  model/  template 없음  provider/  git/  engine/  storage/  platform/  ui/
shared/src/jvmMain/kotlin/aiflow/platform/
shared/src/commonTest/kotlin/aiflow/
desktopApp/src/main/kotlin/Main.kt
```
- [ ] 각 패키지에 `package-info` 성격의 빈 파일 또는 README 주석으로 역할 명시

### 3. 플랫폼 인터페이스 (commonMain `platform/`)
- [ ] `ProcessExecutor`
  ```kotlin
  interface ProcessExecutor {
      fun start(spec: ProcessSpec): RunningProcess
  }
  data class ProcessSpec(val command: List<String>, val cwd: String, val stdin: String? = null, val env: Map<String,String> = emptyMap())
  interface RunningProcess {
      val stdout: Flow<String>   // 라인 단위
      val stderr: Flow<String>
      suspend fun awaitExit(): Int
      suspend fun killTreeAndWait() // 자식 포함 종료 요청 + 제한된 종료 대기, 정리 실패는 명시적 오류
  }
  ```
- [ ] `FileSystem` (okio `FileSystem` 그대로 사용 — 별도 인터페이스 불필요, DI로 주입)
- [ ] `TempFiles` : `createScript(content): Path`, 정리 메서드
- [ ] `Notifier` : `notify(title, body)`
- [ ] `PathDetector` : `suspend fun detect(binary: String): String?`
- [ ] `RepositoryLock`: 저장소별 단일 앱 소유권 잠금 획득/해제. 실패 시 해당 저장소의 쓰기·실행·RunRecovery 차단. 앱 생존 기간 잠금 유지, 프로세스 종료 시 운영체제가 해제하는 잠금 사용
- [ ] `Platform` 묶음 객체(또는 간단한 DI 컨테이너) — 엔진·UI가 이 객체만 받도록

### 4. jvmMain actual 구현 (뼈대)
- [ ] `JvmProcessExecutor`: `ProcessBuilder` 기반, stdout/stderr 각각 별도 코루틴으로 읽어 `Flow` 방출, `stdin` 있으면 쓰고 **반드시 close**. `awaitExit`는 프로세스 종료만 의미하고 스트림 완료와 구분
- [ ] `killTreeAndWait()`는 `ProcessHandle.descendants()` 포함 종료 및 제한된 대기를 수행. start 직후부터 출력 수신/취소를 관리하고, 취소 도중 생성된 프로세스도 즉시 등록·종료하도록 ManagedProcessRunner와 연결
- [ ] Phase 0의 정리 PermissionError 회귀: 권한 거부/종료 대기 초과를 명시적 정리 실패로 반환하되 stdout/stderr close·flush와 결과 저장 경로를 반드시 수행. 무제한 wait, 가짜 종료코드, 정리 실패를 덮는 예외 금지
- [ ] 표준 출력 스트림 종료/수신 오류를 구별해 전달. 프로세스 종료 뒤 남은 출력도 소비하며 파이프 무한 대기는 05의 drain 제한으로 정리
- [ ] `JvmTempFiles`: `java.nio.file.Files.createTempFile`, 실행 권한 부여
- [ ] `MacNotifier`: `osascript -e 'display notification ...'` (ProcessExecutor 재사용)
- [ ] `ZshPathDetector`: `/bin/zsh -lc 'command -v <binary>'`
- [ ] PATH 탐지 결과가 호환성을 보장하지 않음. 명시 경로와 그 경로의 버전을 04/06에 전달. 사용자 머신의 앱 번들 절대 경로를 공통 코드 기본값으로 하드코딩하지 않음
- [ ] `JvmRepositoryLock`: 정규화한 저장소 경로 기준 `.aiflow/app.lock` 파일에 OS 파일 잠금. 같은 JVM의 중복 열기도 하나의 소유자로 관리

### 5. 진입점
- [ ] `desktopApp/Main.kt`: `application { Window(title = "aiflow") { App(platform) } }`
- [ ] `shared/ui/App.kt`: 좌측 네비게이션(편집 / 실행 / 히스토리 / 설정) + 빈 콘텐츠 영역

### 6. 테스트 뼈대
- [ ] `commonTest`에 `FakeProcessExecutor` (스크립트된 stdout/stderr/exitCode 반환) — 05·04 테스트에서 재사용
- [ ] `jvmTest`에 `JvmProcessExecutorTest`: `echo hi`, stdin 전달(`cat`), 실제 종료코드, 종료 직전 출력 수신, stdout/stderr 동시 대량 출력, 자식 프로세스 포함 killTreeAndWait·파이프 정리 검증
- [ ] `RepositoryLock` 테스트: 두 소유자의 동시 획득 차단, 소유자 종료/해제 후 재획득. 파일 존재만으로 잠금 여부를 판단하지 않음
- [ ] 정리 권한 거부·cleanup timeout에도 로그/실제 관측 결과가 남고 추가 실행을 허용하지 않는 경계 테스트. 실제 계정/agent 모델 대신 로컬 프로세스 및 주입된 실패로 검증

## 산출물

- 빌드·실행되는 프로젝트(`./gradlew :desktopApp:run` 으로 창 표시)
- `platform/` 인터페이스 + jvm actual
- `FakeProcessExecutor`, `JvmProcessExecutorTest`

## 완료 기준

- [ ] `./gradlew build` 성공, `:desktopApp:run` 으로 네비게이션이 있는 빈 창 표시
- [ ] `JvmProcessExecutorTest` 통과 (stdin close 포함)
- [ ] `./gradlew :desktopApp:packageDmg` 가 에러 없이 dmg 생성 (내용은 빈 앱이어도 됨)

## 주의

- expect/actual 대신 인터페이스 + 생성자 주입으로 충분하다(타깃이 JVM 하나). 다만 UI·엔진 코드는 `commonMain`에 두는 원칙을 지킨다.
- `ProcessBuilder`에서 stdout/stderr를 동시에 읽지 않으면 버퍼가 차서 데드락이 난다. 반드시 두 스트림을 병렬로 소비할 것.
