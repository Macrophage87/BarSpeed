package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.PlanFile
import com.macrophage.barspeed.model.PlanImport
import kotlinx.coroutines.flow.Flow

/** Human-readable summary of a staged plan, shown in the approval gate. */
data class PlanImportSummary(
    val planId: Long,
    val planName: String,
    val sessionCount: Int,
    val totalSets: Int,
    val exerciseNames: List<String>,
    /** Non-blocking notes from validation, shown at the approval gate. */
    val warnings: List<String> = emptyList(),
    /** Exercises the plan marks droppable when the session runs long. */
    val optionalExercises: List<String> = emptyList(),
)

sealed interface PlanImportResult {
    data class Staged(val summary: PlanImportSummary) : PlanImportResult

    data class Invalid(val errors: List<String>) : PlanImportResult
}

/**
 * There is deliberately no `Json` here. Both places that turn stored text into
 * a [PlanFile] go through [PlanImport.parse], so the staging path and the
 * read-back path cannot decode differently: adding a second decoder would mean
 * adding a second call, not changing a default. That matters because the two
 * used to diverge silently — `importPlan` reported a decode failure and
 * `decode` swallowed it to null, and a plan approved at the gate could still
 * read back as nothing.
 */
class PlanRepository(
    private val planDao: PlanDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val allPlans: Flow<List<PlanEntity>> = planDao.observeAll()
    val activePlan: Flow<PlanEntity?> = planDao.observeActive()

    /**
     * Validate and stage a plan. Never activates automatically: LLM- or
     * human-authored plans always pass through the approval gate (spec 4.4).
     */
    suspend fun importPlan(planJson: String): PlanImportResult {
        val parsed = PlanImport.parse(planJson)
        val plan = parsed.plan ?: return PlanImportResult.Invalid(parsed.errors)
        if (parsed.errors.isNotEmpty()) return PlanImportResult.Invalid(parsed.errors)

        val id =
            planDao.insert(
                PlanEntity(
                    name = plan.planName,
                    json = planJson,
                    importedAtMs = clock(),
                    status = PlanEntity.STATUS_STAGED,
                ),
            )
        return PlanImportResult.Staged(summaryOf(id, plan, parsed.warnings))
    }

    /** The explicit user approval that promotes a staged plan to active. */
    suspend fun activate(planId: Long) = planDao.activate(planId)

    suspend fun delete(planId: Long) = planDao.delete(planId)

    suspend fun plan(planId: Long): PlanEntity? = planDao.byId(planId)

    suspend fun planFile(planId: Long): PlanFile? = planDao.byId(planId)?.let { decode(it) }

    /**
     * Read a stored plan back. Null means the text could not be decoded at all;
     * a plan that decodes and then fails validation is still returned, as it
     * always has been, because it was validated once at the gate and refusing
     * to show it now would only hide it.
     */
    fun decode(entity: PlanEntity): PlanFile? = PlanImport.parse(entity.json).plan

    fun summaryOf(id: Long, plan: PlanFile, warnings: List<String>): PlanImportSummary = PlanImportSummary(
        planId = id,
        planName = plan.planName,
        sessionCount = plan.sessions.size,
        totalSets = plan.sessions.sumOf { s -> s.exercises.sumOf { it.sets.size } },
        exerciseNames = plan.sessions.flatMap { s -> s.exercises.map { it.exercise } }.distinct(),
        warnings = warnings,
        optionalExercises =
        plan.sessions.flatMap { s -> s.exercises.filter { it.optional }.map { it.exercise } }.distinct(),
    )
}
