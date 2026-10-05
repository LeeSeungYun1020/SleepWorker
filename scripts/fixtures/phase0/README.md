# Phase 0 보존 자료

임시 `scripts/samples/`를 정리하기 전에 파서 회귀에 필요한 CLI 원문과 주요 검증 요약을
선별했다. `manifest.json`에 파일별 SHA-256과 바이트 수를 기록했으며 원본과 일치함을 확인했다.
합계 137개 파일, 82,392바이트(이 안내문과 manifest 제외).

- `codex/`: 버전/도움말, new/resume, 로그인 상태, 잘못된 모델, stdin·중단, 구버전 실패.
- `agy/`: 버전/도움말, TSV 모델 목록, 미지원 옵션, new/resume, 잘못된 모델.
- `additional/`: 다른 cwd 재개, 취소, 미인증 Codex, 명시 모델, 셸 alias, GitHub 게시·정리 증거.
- `pipeline/`: 로컬 5단계 검증의 결과·단계 요약. 전체 대화 로그는 삭제했다.
- `synthetic/`: 수작업 파서 경계 사례. 실측이 아니며 result.json의 source로 구분한다.

stdout/stderr와 결과 메타데이터는 수정하지 않았다. cwd/argv의 과거 절대 경로와
summary의 예전 증거 경로는 역사적 기록이며, 현재 실행 가능한 경로가 아니다.
stdin 원문, 임시 저장소·워크트리, 격리 인증 홈, 중복 probe·setup 로그, 실패한 수집기의
불완전 로그는 삭제했다. 실제 사용자 인증/CLI 세션 저장소와 원본 Manicule은 정리 대상이 아니다.

Phase 04에서 필요한 fixture를 테스트 리소스로 가져간다. 이 자료는 CLI 계약 증거이며
향후 앱 end-to-end 검증 완료나 agy 미로그인 오류 검증 완료를 의미하지 않는다.
