package aiflow.ui.components

import aiflow.engine.*


fun RunStatus.label(): String = when(this) {
    RunStatus.IDLE -> "대기"; RunStatus.PREFLIGHT -> "사전 검사 중"; RunStatus.RUNNING -> "실행 중"; RunStatus.PAUSE_REQUESTED -> "일시정지 예약"; RunStatus.PAUSED -> "일시정지"; RunStatus.AWAITING_USER -> "확인 필요"; RunStatus.COMPLETED -> "완료"; RunStatus.FAILED -> "실패"; RunStatus.ABORTED -> "중지됨"; RunStatus.INTERRUPTED -> "중단된 기록"
}
fun StepStatus.label(): String = when(this) {
    StepStatus.PENDING -> "대기"; StepStatus.PREPARING -> "준비 중"; StepStatus.EXECUTING -> "실행 중"; StepStatus.FINALIZING -> "출력 정리 중"; StepStatus.CHECKING -> "검사 중"; StepStatus.SUCCEEDED -> "성공"; StepStatus.RETRYING -> "재시도 중"; StepStatus.AWAITING_USER -> "확인 필요"; StepStatus.SKIPPED -> "건너뜀"; StepStatus.FAILED -> "실패"; StepStatus.INTERRUPTED -> "중단됨"
}
fun PreflightStatus.label(): String = when(this) { PreflightStatus.OK -> "통과"; PreflightStatus.INFO -> "안내"; PreflightStatus.WARN -> "경고"; PreflightStatus.ERROR -> "오류" }
fun aiflow.provider.Termination.label(): String = when(this) { aiflow.provider.Termination.NORMAL -> "정상 종료"; aiflow.provider.Termination.TIMED_OUT -> "시간 초과"; aiflow.provider.Termination.CANCELLED -> "취소됨"; aiflow.provider.Termination.START_FAILED -> "시작 실패"; aiflow.provider.Termination.OUTPUT_INCOMPLETE -> "출력 미완료" }
fun aiflow.provider.ProviderOutcome.label(): String = when(this) { aiflow.provider.ProviderOutcome.SUCCEEDED -> "성공"; aiflow.provider.ProviderOutcome.FAILED -> "실패"; aiflow.provider.ProviderOutcome.PROTOCOL_ERROR -> "응답 형식 오류"; aiflow.provider.ProviderOutcome.NOT_APPLICABLE -> "해당 없음" }
fun aiflow.provider.FailureKind.label(): String = when(this) { aiflow.provider.FailureKind.PREPARATION -> "준비 실패"; aiflow.provider.FailureKind.AUTH -> "인증"; aiflow.provider.FailureKind.QUOTA -> "사용량 제한"; aiflow.provider.FailureKind.CONFIG -> "설정"; aiflow.provider.FailureKind.SESSION_MISSING -> "세션 없음"; aiflow.provider.FailureKind.SESSION_INVALID -> "잘못된 세션"; aiflow.provider.FailureKind.PROVIDER -> "제공자 오류"; aiflow.provider.FailureKind.PROTOCOL -> "응답 형식"; aiflow.provider.FailureKind.EXIT_CODE -> "명령 실패"; aiflow.provider.FailureKind.TIMEOUT -> "시간 초과"; aiflow.provider.FailureKind.OUTPUT_IO -> "출력 수신"; aiflow.provider.FailureKind.COMPLETION -> "완료 확인" }
fun aiflow.model.Workspace.label(): String = when(this) { aiflow.model.Workspace.Local -> "로컬"; is aiflow.model.Workspace.Worktree -> "워크트리 $name" }
fun aiflow.model.SessionMode.label(): String = when(this) { aiflow.model.SessionMode.NEW -> "새 세션"; aiflow.model.SessionMode.RESUME -> "이어가기" }
