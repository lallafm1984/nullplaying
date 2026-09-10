package com.nullplaying.engine.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSupportAuditTest {
    @Test
    fun `level twenty smoke audit covers A01 and A02 cohorts without ledger failures`() {
        val report = ArenaSupportAudit.run(samplesPerPair = 1, levels = listOf(20))
        assertTrue(report.failures.joinToString("\n"), report.passed)
        assertEquals(567, report.executions)

        val root = Json.parseToJsonElement(report.json).jsonObject
        assertEquals("arena-support-audit-v3", root.getValue("auditVersion").jsonPrimitive.content)
        assertEquals(9, root.getValue("cohorts").jsonArray.size)
        assertEquals(144, root.getValue("traitCoverage").jsonObject.size)
        assertEquals(0, root.getValue("failureCount").jsonPrimitive.content.toInt())
    }
}
