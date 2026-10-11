package sleepworker.engine

import sleepworker.model.*
import sleepworker.model.Target

class TransitionEvaluator(private val checks: CheckRunner) {
    data class Evaluation(val decision: Decision, val selected: TransitionTaken?, val cache: Map<Int, CheckRecord>)
    suspend fun evaluate(step: Step, success: Boolean, workspace: String, visit: StepVisit, counts: Map<String, Int>, path: String,
                         onCheck: suspend (Int, CheckRecord) -> Unit = { _, _ -> }): Evaluation {
        val cache = visit.conditionEvaluations.toMutableMap()
        for ((index, transition) in step.transitions.withIndex()) {
            val result = when (transition.`when`) {
                Condition.Success -> if (success) CheckResult.Met else CheckResult.NotMet
                Condition.Failure -> if (!success) CheckResult.Met else CheckResult.NotMet
                Condition.Otherwise -> CheckResult.Met
                else -> cache.getOrPutSuspend(index) {
                    checks.condition(transition.`when`, workspace, step.checkTimeoutSec, "$path/$index").also { onCheck(index, it) }
                }.result
            }
            if (result is CheckResult.Error) return Evaluation(Decision.Ask("condition evaluation error: ${result.reason}"), null, cache)
            if (result != CheckResult.Met) continue
            val taken = TransitionTaken(step.id, index, transition.`when`, transition.next)
            if (visit.transitionTaken == taken && transition.next == Target.Ask) return Evaluation(Decision.Ask("explicit ask"), null, cache)
            if (transition.maxVisits != null && (counts[key(step.id, index)] ?: 0) >= transition.maxVisits)
                return Evaluation(Decision.Ask("transition maxVisits reached"), null, cache)
            val decision = when (val target = transition.next) {
                Target.End -> Decision.End
                Target.Ask -> Decision.Ask("explicit ask")
                is Target.StepId -> Decision.NextStep(target.id, transition.resetSession, index)
            }
            return Evaluation(decision, taken, cache)
        }
        return Evaluation(Decision.Ask("no transition matched"), null, cache)
    }
    private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(key: K, block: suspend () -> V): V = get(key) ?: block().also { put(key, it) }
    companion object { fun key(stepId: String, index: Int) = "$stepId:$index" }
}
