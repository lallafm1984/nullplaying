package com.nullplaying.ui

import com.nullplaying.model.HeroStats
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android application, database, Firebase, or network is initialized by these checks. */
class StatGuideContentTest {
    private fun projectFile(path: String): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/simple/java").isDirectory }
        return File(root, path)
    }

    private fun catalog(language: String): Map<String, String> = projectFile(
        "app/src/simple/res/raw/localization_$language.tsv",
    ).readLines().filter { it.startsWith("E\t") }.associate { row ->
        val parts = row.split('\t')
        String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8) to
            String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
    }

    private fun guideStrings() = listOf(
        StatGuideContent.title, StatGuideContent.introduction, StatGuideContent.classBenefit, "닫기",
    ) + StatGuideContent.entries.flatMap { listOf(it.title, it.benefit) }

    @Test
    fun `guide covers every displayed stat in the same order`() {
        assertEquals(HeroStats.labels, StatGuideContent.entries.map { it.statLabel })
        assertEquals(8, StatGuideContent.entries.map { it.statLabel }.distinct().size)
        assertTrue(StatGuideContent.entries.all { it.title.isNotBlank() && it.benefit.isNotBlank() })
    }

    @Test
    fun `guide explains benefits without exposing formulas caps or exact balance numbers`() {
        val exactNumbers = Regex("[0-9%×=]")
        val koreanFormulas = listOf("계수", "상한", "확률표", "산식")
        guideStrings().forEach { text ->
            assertFalse(text, exactNumbers.containsMatchIn(text))
            assertFalse(text, koreanFormulas.any(text::contains))
        }
        assertTrue(StatGuideContent.entries.all { it.benefit.length <= 55 })
        assertEquals(
            StatGuideContent.entries.single { it.statLabel == "INT" }.benefit,
            StatGuideContent.entries.single { it.statLabel == "WIS" }.benefit,
        )
    }

    @Test
    fun `english and japanese contain exact translations for the entire guide`() {
        for (language in listOf("en", "ja")) {
            val translations = catalog(language)
            guideStrings().forEach { source ->
                val target = translations[source]
                assertTrue("$language missing $source", !target.isNullOrBlank())
                assertFalse("$language leaked Korean: $source", Regex("[가-힣]").containsMatchIn(target.orEmpty()))
                assertFalse("$language exposed numbers: $source", Regex("[0-9%×=]").containsMatchIn(target.orEmpty()))
                assertFalse("$language has unresolved placeholder: $source", target.orEmpty().contains("{{"))
            }
        }
        assertEquals("Stat Guide", catalog("en")[StatGuideContent.title])
        assertEquals("能力値ガイド", catalog("ja")[StatGuideContent.title])
        assertEquals("Close", catalog("en")["닫기"])
        assertEquals("閉じる", catalog("ja")["닫기"])
    }

    @Test
    fun `both screens use the shared guide without creating or saving a character`() {
        val screen = projectFile("app/src/simple/java/com/nullplaying/ui/AlarmQuestApp.kt").readText()
        val creation = screen.substringAfter("private fun CharacterCreation(").substringBefore("private fun ClassGrid(")
        val adventurer = screen.substringAfter("private fun CharacterPanel(").substringBefore("private fun CharacterStatSheet(")
        assertEquals(1, Regex("StatSectionHeading\\(").findAll(creation).count())
        assertEquals(1, Regex("StatSectionHeading\\(").findAll(adventurer).count())
        val shared = projectFile("app/src/simple/java/com/nullplaying/ui/StatGuide.kt").readText()
        assertTrue(shared.contains("onDismissRequest = onDismiss"))
        assertTrue(shared.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(shared.contains("focusManager.clearFocus()"))
        assertTrue(shared.contains("Icons.Outlined.Info"))
        assertTrue(shared.contains("border = BorderStroke("))
        assertTrue(shared.contains("color = AqGold.copy(alpha = if (enabled) 0.1f else 0.04f)"))
        for (forbidden in listOf("SimpleGameRepository", "Supabase", "Firebase", "createCharacter", "rollStats")) {
            assertFalse(forbidden, shared.contains(forbidden))
        }
    }

    @Test
    fun `badge outline stays compact without shrinking the material touch target`() {
        val shared = projectFile("app/src/simple/java/com/nullplaying/ui/StatGuide.kt").readText()
        val button = shared.substringAfter("private fun StatGuideButton(")
            .substringBefore("private fun StatGuideDialog(")
        assertTrue(button.contains("Surface("))
        assertTrue(button.contains("role = Role.Button"))
        assertTrue(button.contains("RoundedCornerShape(percent = 50)"))
        assertTrue(button.contains(".heightIn(min = 28.dp)"))
        assertTrue(button.contains(".padding(horizontal = 8.dp, vertical = 3.dp)"))
        assertFalse(button.contains(".heightIn(min = 48.dp)"))
        assertFalse(button.contains("LocalMinimumInteractiveComponentSize"))
    }

    @Test
    fun `compact popup uses available screen height and keeps overflow accessible`() {
        val shared = projectFile("app/src/simple/java/com/nullplaying/ui/StatGuide.kt").readText()
        val dialog = shared.substringAfter("private fun StatGuideDialog(")
        assertTrue(dialog.contains("fontSize = 18.sp"))
        assertTrue(dialog.contains("fontSize = 12.sp"))
        assertTrue(dialog.contains("lineHeight = 17.sp"))
        assertTrue(dialog.contains("Arrangement.spacedBy(8.dp)"))
        assertTrue(dialog.contains(".verticalScroll(rememberScrollState())"))
        assertFalse(dialog.contains(".heightIn(max ="))
        assertTrue(dialog.contains("maxLines = Int.MAX_VALUE - 1"))
    }

    @Test
    fun `creation stat total is trailing aligned independently from the title and badge`() {
        val shared = projectFile("app/src/simple/java/com/nullplaying/ui/StatGuide.kt").readText()
        val heading = shared.substringAfter("internal fun StatSectionHeading(")
            .substringBefore("private fun StatGuideButton(")
        assertTrue(heading.contains("modifier = modifier.fillMaxWidth()"))
        assertTrue(heading.contains("modifier = Modifier.weight(1f)"))
        val total = heading.substringAfter("if (trailingText != null)")
        assertTrue(total.contains("Modifier.padding(start = 8.dp, end = 4.dp)"))
        assertFalse(total.contains("StatGuideButton("))
    }
}
