package com.gerald.pillagercampaigns.system

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertTrue

class FormulaicArchitectureTest {
    @Test fun `runtime contains no fixed roster or origin classifications`() {
        val forbidden = Regex("(?i)(warband.?archetype|combat.?style|warband.?role|structure.?id|fallback.?kit)")
        val violations = Files.walk(Path("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line -> if (forbidden.containsMatchIn(line)) "$path:${index + 1}" else null }.stream()
            }.toList()
        }
        assertTrue(violations.isEmpty(), "fixed classification leaked into runtime: $violations")
    }
}
