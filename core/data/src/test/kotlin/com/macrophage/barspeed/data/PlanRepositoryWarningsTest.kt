package com.macrophage.barspeed.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The warnings the plan import gate shows, and where they go after it (#58).
 *
 * `importPlan` stores the row as staged BEFORE the approval dialog renders,
 * the dialog's dismiss only clears it, and `PlanEntity` stores the JSON and
 * nothing else. So a plan whose set wrote `loadKg` for `load_kg` -- warned
 * about in a dialog the lifter dismissed -- sits one "Make active" tap from
 * active with its warning destroyed, and then records every set at no load.
 */
class PlanRepositoryWarningsTest {
    private class FakePlanDao : PlanDao {
        val rows = mutableMapOf<Long, PlanEntity>()

        override suspend fun insert(plan: PlanEntity): Long {
            val id = rows.size + 1L
            rows[id] = plan.copy(id = id)
            return id
        }

        override suspend fun update(plan: PlanEntity) {
            rows[plan.id] = plan
        }

        override fun observeAll(): Flow<List<PlanEntity>> = flowOf(rows.values.toList())

        override fun observeActive(): Flow<PlanEntity?> =
            flowOf(rows.values.firstOrNull { it.status == PlanEntity.STATUS_ACTIVE })

        override suspend fun byId(id: Long): PlanEntity? = rows[id]

        override suspend fun archiveActive() {
            rows.replaceAll { _, row ->
                if (row.status == PlanEntity.STATUS_ACTIVE) row.copy(status = PlanEntity.STATUS_ARCHIVED) else row
            }
        }

        override suspend fun markActive(id: Long) {
            rows[id] = rows.getValue(id).copy(status = PlanEntity.STATUS_ACTIVE)
        }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }
    }

    private fun plan(set: String) =
        """
        {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"back_squat","sets":[$set]}
        ]}]}
        """.trimIndent()

    /** A near miss on the load key: the set imports with no load at all. */
    private val nearMiss = plan("""{"reps":5,"loadKg":100}""")

    @Test
    fun `a stored staged plan gives back the warnings its import showed`() = runTest {
        val dao = FakePlanDao()
        val repository = PlanRepository(dao, clock = { 0L })
        val staged = repository.importPlan(nearMiss) as PlanImportResult.Staged
        assertTrue(staged.summary.warnings.isNotEmpty(), "premise: the near miss is warned about")

        val review = repository.reviewSummary(dao.byId(staged.summary.planId)!!)!!
        assertEquals(staged.summary, review)
    }

    @Test
    fun `a stored plan with nothing to warn about reviews with no warnings`() = runTest {
        val dao = FakePlanDao()
        val repository = PlanRepository(dao, clock = { 0L })
        val staged = repository.importPlan(plan("""{"reps":5,"load_kg":100}""")) as PlanImportResult.Staged
        assertEquals(emptyList(), repository.reviewSummary(dao.byId(staged.summary.planId)!!)!!.warnings)
    }
}
