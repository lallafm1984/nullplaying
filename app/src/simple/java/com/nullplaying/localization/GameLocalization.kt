package com.nullplaying.localization

import android.content.Context
import android.util.Base64
import androidx.annotation.RawRes
import com.nullplaying.R
import java.util.Collections
import java.util.LinkedHashMap

object GameLocalization {
    private data class LocalizedPattern(
        val regex: Regex,
        val targetTemplate: String,
    )

    private data class Catalog(
        val exact: Map<String, String>,
        val patterns: List<LocalizedPattern>,
        val fragments: List<Pair<String, String>>,
    )

    private val emptyCatalog = Catalog(emptyMap(), emptyList(), emptyList())
    private val resultCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > CACHE_LIMIT
        },
    )

    @Volatile
    private var englishCatalog: Catalog = emptyCatalog

    @Volatile
    private var japaneseCatalog: Catalog = emptyCatalog

    @Volatile
    private var activeLanguage: AppLanguage = AppLanguage.fromDevice()

    fun initialize(context: Context) {
        englishCatalog = loadCatalog(context, R.raw.localization_en)
        japaneseCatalog = loadCatalog(context, R.raw.localization_ja)
        resultCache.clear()
    }

    fun setActiveLanguage(language: AppLanguage) {
        activeLanguage = language
    }

    fun translateCurrent(text: String): String = translate(text, activeLanguage)

    fun translateCurrentPreserving(text: String, protectedValues: Collection<String>): String =
        translatePreserving(text, activeLanguage, protectedValues)

    fun translateCurrentPreserving(text: String, protectedValues: Map<String, String>): String =
        translatePreserving(text, activeLanguage, protectedValues)

    /**
     * Translates authored UI copy while keeping user-provided values, such as nicknames, intact.
     */
    fun translatePreserving(
        text: String,
        language: AppLanguage,
        protectedValues: Collection<String>,
    ): String = translatePreserving(
        text = text,
        language = language,
        protectedValues = protectedValues.associateWith { it },
    )

    fun translatePreserving(
        text: String,
        language: AppLanguage,
        protectedValues: Map<String, String>,
    ): String {
        if (language == AppLanguage.KOREAN || text.isEmpty()) return text
        val replacements = linkedMapOf<String, String>()
        var protectedText = text
        protectedValues
            .asSequence()
            .filter { (source, _) -> source.isNotEmpty() }
            .distinctBy { (source, _) -> source }
            .sortedByDescending { (source, _) -> source.length }
            .forEachIndexed { index, (source, restoredValue) ->
                if (protectedText.contains(source)) {
                    val token = "\uE000AQ_RAW_$index\uE001"
                    protectedText = protectedText.replace(source, token)
                    replacements[token] = restoredValue
                }
            }
        var translated = translate(protectedText, language)
        replacements.forEach { (token, value) ->
            translated = translated.replace(token, value)
        }
        return translated
    }

    fun translate(text: String, language: AppLanguage): String {
        if (language == AppLanguage.KOREAN || text.isEmpty()) return text
        val cacheKey = "${language.languageTag}\u0000$text"
        return resultCache[cacheKey] ?: translateUncached(text, language).also {
            resultCache[cacheKey] = it
        }
    }

    private fun translateUncached(text: String, language: AppLanguage): String {
        val catalog = when (language) {
            AppLanguage.ENGLISH -> englishCatalog
            AppLanguage.JAPANESE -> japaneseCatalog
            AppLanguage.KOREAN -> return text
        }
        catalog.exact[text]?.let { return it }
        catalog.patterns.forEach { pattern ->
            val match = pattern.regex.matchEntire(text) ?: return@forEach
            var translated = pattern.targetTemplate
            for (index in 1 until match.groupValues.size) {
                val localizedValue = translate(match.groupValues[index], language)
                translated = translated.replace("{{$index}}", localizedValue)
            }
            return translated
        }
        // Dynamic Korean names are rendered with their grammatical particle before they reach
        // localization (for example, "수로 슬라임을"). Pattern placeholders must receive only
        // the localized name because the target sentence supplies its own grammar.
        KOREAN_TRAILING_PARTICLES.firstOrNull(text::endsWith)?.let { particle ->
            catalog.exact[text.dropLast(particle.length)]?.let { return it }
        }
        if (!text.contains(KOREAN_TEXT)) return text

        // Covers sentences assembled with buildString/append while keeping the longer, structured
        // full-string and template matches above as the preferred path.
        var translated = text
        catalog.fragments.forEach { (source, target) ->
            if (translated.contains(source)) translated = translated.replace(source, target)
        }
        return translated
    }

    private fun loadCatalog(context: Context, @RawRes resourceId: Int): Catalog {
        val exact = linkedMapOf<String, String>()
        val patterns = mutableListOf<LocalizedPattern>()
        context.resources.openRawResource(resourceId).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val parts = line.split('\t', limit = 3)
                require(parts.size == 3) { "Invalid localization catalog row" }
                val source = decode(parts[1])
                val target = decode(parts[2])
                when (parts[0]) {
                    "E" -> exact.putIfAbsent(source, target)
                    "P" -> patterns += LocalizedPattern(templateRegex(source), target)
                    else -> error("Unknown localization catalog row type")
                }
            }
        }
        val fragments = exact.entries
            .asSequence()
            .filter { (source, _) -> source.length >= MIN_FRAGMENT_LENGTH && source.contains(KOREAN_TEXT) }
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
            .toList()
        return Catalog(exact, patterns, fragments)
    }

    private fun templateRegex(template: String): Regex {
        val regex = StringBuilder("^")
        var cursor = 0
        TEMPLATE_TOKEN.findAll(template).forEach { match ->
            regex.append(Regex.escape(template.substring(cursor, match.range.first)))
            regex.append("(.*?)")
            cursor = match.range.last + 1
        }
        regex.append(Regex.escape(template.substring(cursor)))
        regex.append('$')
        return Regex(regex.toString(), setOf(RegexOption.DOT_MATCHES_ALL))
    }

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private const val CACHE_LIMIT = 2_048
    private const val MIN_FRAGMENT_LENGTH = 2
    private val KOREAN_TRAILING_PARTICLES = listOf("으로", "에서", "에게", "은", "는", "이", "가", "과", "와", "을", "를")
    private val KOREAN_TEXT = Regex("[가-힣]")
    private val TEMPLATE_TOKEN = Regex("\\{\\{[1-9][0-9]*\\}\\}")
}
