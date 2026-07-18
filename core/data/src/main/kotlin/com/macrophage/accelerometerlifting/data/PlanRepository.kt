package com.macrophage.accelerometerlifting.data

import com.macrophage.accelerometerlifting.model.PlanFile
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** Human-readable summary of a staged plan, shown in the approval gate. */
data class PlanImportSummary(
    val planId: Long,
    val planName: String,
    val sessionCount: Int,
    val totalSets: Int,
    val exerciseNames: List<String>,
)

sealed interface PlanImportResult {
    data class Staged(val summary: PlanImportSummary) : PlanImportResult

    data class Invalid(val errors: List<String>) : PlanImportResult
}

class PlanRepository(
    private val planDao: PlanDao,
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val allPlans: Flow<List<PlanEntity>> = planDao.observeAll()
    val activePlan: Flow<PlanEntity?> = planDao.observeActive()

    /**
     * Validate and stage a plan. Never activates automatically: LLM- or
     * human-authored plans always pass through the approval gate (spec 4.4).
     */
    suspend fun importPlan(planJson: String): PlanImportResult {
        val plan =
            try {
                json.decodeFromString(PlanFile.serializer(), planJson)
            } catch (e: Exception) {
                return PlanImportResult.Invalid(
                    listOf("Not valid plan JSON: ${e.message?.lineSequence()?.first() ?: "parse error"}"),
                )
            }
        val errors = plan.validate()
        if (errors.isNotEmpty()) return PlanImportResult.Invalid(errors)

        val id =
            planDao.insert(
                PlanEntity(
                    name = plan.planName,
                    json = planJson,
                    importedAtMs = clock(),
                    status = PlanEntity.STATUS_STAGED,
                ),
            )
        return PlanImportResult.Staged(summaryOf(id, plan))
    }

    /** The explicit user approval that promotes a staged plan to active. */
    suspend fun activate(planId: Long) = planDao.activate(planId)

    suspend fun delete(planId: Long) = planDao.delete(planId)

    suspend fun planFile(planId: Long): PlanFile? = planDao.byId(planId)?.let { decode(it) }

    fun decode(entity: PlanEntity): PlanFile? = try {
        json.decodeFromString(PlanFile.serializer(), entity.json)
    } catch (e: Exception) {
        null
    }

    fun summaryOf(id: Long, plan: PlanFile): PlanImportSummary = PlanImportSummary(
        planId = id,
        planName = plan.planName,
        sessionCount = plan.sessions.size,
        totalSets = plan.sessions.sumOf { s -> s.exercises.sumOf { it.sets.size } },
        exerciseNames = plan.sessions.flatMap { s -> s.exercises.map { it.exercise } }.distinct(),
    )
}
