package ly.anoma.hex.speech

import java.util.Locale

/** Removes speech disfluencies locally while keeping quotations and ambiguous words in context. */
internal object DictationCleanup {
    private const val wordCharacter = "[\\p{L}\\p{M}\\p{N}_@'’‑-]"
    private fun words(pattern: String) = Regex("(?iu)(?<!$wordCharacter)(?:$pattern)(?!$wordCharacter)")

    private val russianPauses = words("э+(?:[-‑–—]э+)*м*(?:[-‑–—]м+)*|м+(?:[-‑–—]м+)*|гм+|хм+")
    private val ukrainianPauses = words("е{2,}|е+(?:[-‑–—]е+)+|ем+|гм+|м{2,}")
    private val englishPauses = words("um+|uh+|erm+|u+h+m*|hmm+")
    private val russianFillers = words(
        "ну(?:[ \\t]+(?:короче|вот))?|короче(?:[ \\t]+говоря)?|в[ \\t]+общем(?:[-‑]то)?|" +
            "как[ \\t]+бы(?:[ \\t]+вот)?|типа|так[ \\t]+сказать|это[ \\t]+самое|значит|вот|" +
            "собственно(?:[ \\t]+говоря)?",
    )
    private val letterNames = setOf("буква", "букву", "буквы", "буквой", "звук", "звука", "литера", "литеру")
    private val subjects = setOf("я", "мы", "он", "она", "они", "ты", "вы", "это")
    private val personalWords = subjects - "это" + setOf("мне", "нам", "ему", "ей", "им", "тебе", "вам")
    private val nuResponses = setOf("а", "бы", "всё", "да", "его", "её", "их", "зачем", "и", "как", "какой",
        "когда", "конечно", "кто", "куда", "ладно", "не", "нет", "ну", "почему", "сколько", "так", "тебя",
        "уж", "хорошо", "чего", "что", "чтобы", "где")
    private val generalStatement = subjects + setOf("будем", "всё", "давайте", "завтра", "можно", "надо", "нужно",
        "потом", "просто", "сегодня", "сейчас", "теперь")
    private val narrative = generalStatement + setOf("вчера", "думал", "думала", "думаю", "пойду", "получается",
        "пошёл", "пошла", "решил", "решила", "решили", "сказал", "сказала", "собираюсь", "хотел", "хотела", "хочу")
    private val firstPersonEndings = listOf("аю", "яю", "ую", "ею", "лю", "рю", "юсь")
    private val nonThirdPerson = setOf("я", "мы", "ты", "вы", "они")
    private val relativeWords = setOf("который", "которого", "которому", "которым", "котором", "которая",
        "которой", "которую", "которое", "которые", "которых", "когда", "куда", "где", "что", "чтобы")
    private val commaContinuations = setOf("а", "где", "дабы", "если", "и", "или", "как",
        "какой", "когда", "который", "куда", "либо", "но", "пока", "поскольку", "потому", "причём", "хотя",
        "чем", "что", "чтобы", "but", "if", "so", "that", "what", "when", "where", "whether", "which") + relativeWords
    private val emphasis = setOf("да", "нет", "не", "ни", "очень", "еле", "точно", "тихо", "сильно", "долго",
        "быстро", "быстрее", "вот", "давай", "скорее", "совсем", "чуть", "пожалуйста", "ні", "дуже", "no", "not",
        "yes", "really", "very")
    private val restarts = subjects + setOf("в", "к", "на", "по", "с", "у", "мне", "меня", "уже", "то",
        "сегодня", "завтра", "вчера", "сейчас", "потом")
    private val duplicate = Regex("(?iu)(?<!$wordCharacter)(\\p{L}+)(?:[ \\t]+\\1(?!$wordCharacter))+")
    private val commaRestart = Regex("(?iu)(?<!$wordCharacter)(${restarts.joinToString("|")})" +
        "(?:[ \\t]*,[ \\t]*\\1(?!$wordCharacter))+(?:[ \\t]*(,))?")
    private val stutter = Regex("(?iu)(?<!$wordCharacter)([бвгджзклмнпрстфхцчшщ])[-‑]" +
        "(?:\\1[-‑])*(\\1[а-яё]{2,})(?!$wordCharacter)")
    private fun phraseRestart(openers: String) = words("((?:$openers)(?:[ \\t]+\\p{L}+){1,3})" +
        "[ \\t]*(?:[,–—…]|\\.\\.\\.)?[ \\t]+\\1")
    private val russianRestart = phraseRestart((subjects + "мне" + "нам").joinToString("|"))
    private val englishRestart = phraseRestart("i|we|you|he|she|they|it")
    private const val timeSlot = "понедельник(?:а|у|е)?|вторник(?:а|у|е)?|сред[ауеы]|четверг(?:а|у|е)?|" +
        "пятниц[аеуы]|суббот[аеуы]|воскресень[еяюи]|час|два|три|четыре|пять|шесть|семь|восемь|девять|" +
        "десять|одиннадцать|двенадцать|\\d{1,2}(?::\\d{2})?"
    private const val EDIT_MARKER = "[ \\t]*[,.–—…]?[ \\t]+(?:точнее|вернее|(?:ой[ \\t]+)?нет)[ \\t]*,?[ \\t]+"
    private val scheduleCorrection = words("(в|во|до|на|с|со|после|к|ко)[ \\t]+(?:$timeSlot)" + EDIT_MARKER +
        "(\\1[ \\t]+(?:$timeSlot))(?![ \\t]+(?:${subjects.joinToString("|")})(?!$wordCharacter))")
    private const val relativeDay = "сегодня|вчера|завтра|позавчера|послезавтра"
    private val dateCorrection = words("(?:$relativeDay)$EDIT_MARKER($relativeDay)" +
        "(?![ \\t]+(?:${subjects.joinToString("|")})(?!$wordCharacter))")
    private val firstWord = Regex("^[,.;:!?… \\t]*([\\p{L}\\p{N}]+)")
    private val lastWord = Regex("([\\p{L}]+)[,;: \\t]*$")
    private val horizontalWhitespace = Regex("[ \\t]+")

    fun clean(text: String, language: String): String = buildString {
        var start = 0
        var closing: Char? = null
        for ((i, ch) in text.withIndex()) {
            if (closing == null && ch in "\"«“") {
                append(cleanUnquoted(text.substring(start, i), language))
                start = i
                closing = when (ch) { '«' -> '»'; '“' -> '”'; else -> '"' }
            } else if (ch == closing) {
                append(text.substring(start, i + 1))
                start = i + 1
                closing = null
            }
        }
        append(if (closing == null) cleanUnquoted(text.substring(start), language) else text.substring(start))
    }

    private fun cleanUnquoted(text: String, language: String): String {
        if (text.isBlank()) return text
        val pauses = when (language) { "ru" -> russianPauses; "uk" -> ukrainianPauses; else -> englishPauses }
        var cleaned = removeFillers(text.trim(' ', '\t'), pauses) { phrase, left, right ->
            language != "ru" || isRussianPause(phrase, left, right)
        }
        if (language == "ru") {
            cleaned = removeFillers(cleaned, russianFillers, ::isRussianFiller)
            cleaned = correctRevision(cleaned, scheduleCorrection, 2)
            cleaned = correctRevision(cleaned, dateCorrection, 1)
            cleaned = stutter.replace(cleaned) { it.groupValues[2] }
            val beforeRestarts = cleaned
            cleaned = commaRestart.replace(beforeRestarts) { match ->
                val tail = beforeRestarts.substring(match.range.last + 1)
                val comma = match.groupValues[2].takeIf { followingWord(tail) in commaContinuations }.orEmpty()
                match.groupValues[1] + comma
            }
        }
        cleaned = when (language) {
            "ru" -> russianRestart.replace(cleaned) { it.groupValues[1] }
            "en" -> englishRestart.replace(cleaned) { it.groupValues[1] }
            else -> cleaned
        }
        val deduplicated = duplicate.replace(cleaned) { match ->
            val word = match.groupValues[1]
            val lower = word.lowercase(Locale.ROOT)
            if (lower !in emphasis && (word.length >= 3 || language == "ru" && lower in restarts)) word else match.value
        }
        return text.takeWhile { it == ' ' || it == '\t' } + deduplicated + text.takeLastWhile { it == ' ' || it == '\t' }
    }

    private fun isRussianPause(phrase: String, left: String, right: String) = when (phrase) {
        "м" -> (isParenthetical(left, right) || left.endsWith(',') &&
            (right.startsWith("...") || right.startsWith('…'))) && followingWord(right).length > 1
        "э", "эм" -> left.endsWith(',') || previousWord(left) !in letterNames
        else -> true
    }

    private fun correctRevision(text: String, pattern: Regex, replacement: Int) = pattern.replace(text) { match ->
        if (previousWord(text.substring(0, match.range.first)) == "не") match.value
        else match.groupValues[replacement]
    }

    private fun isRussianFiller(phrase: String, left: String, right: String): Boolean {
        val next = followingWord(right)
        val start = beginsSentence(left)
        val previous = previousWord(left)
        val parenthetical = isParenthetical(left, right)
        // Without commas, require a likely continuation of speech before removing an ambiguous word.
        val firstPersonVerb = firstPersonEndings.any { next.endsWith(it) }
        val narrativeContinuation = next in narrative || firstPersonVerb
        return when (phrase) {
            "ну", "ну вот", "ну короче" -> previous != "а" && previous != "да" &&
                ((!start && left.endsWith(',') && next in relativeWords) || next !in nuResponses &&
                    (next.isNotEmpty() || phrase == "ну" && right.all { it in " ,.…\t" }))
            "короче" -> parenthetical || (start || previous in personalWords) && narrativeContinuation
            "в общем" -> parenthetical || next in generalStatement || firstPersonVerb
            "короче говоря", "так сказать" -> parenthetical || (start || left.endsWith(',')) && right.isNotEmpty()
            "типа", "как бы", "как бы вот", "это самое" -> parenthetical ||
                (previous in personalWords || left.endsWith(',')) && narrativeContinuation
            "значит" -> !start && parenthetical || previous in nonThirdPerson && narrativeContinuation
            "собственно говоря" -> parenthetical || start && next in personalWords
            "собственно" -> start && right.startsWith(',') && next in personalWords
            else -> parenthetical && next.isNotEmpty()
        }
    }

    private fun removeFillers(text: String, pattern: Regex, removable: (String, String, String) -> Boolean): String {
        var remaining = text
        var offset = 0
        while (true) {
            val match = pattern.find(remaining, offset) ?: return remaining
            val left = remaining.substring(0, match.range.first).trimEnd(' ', '\t')
            val right = remaining.substring(match.range.last + 1).trimStart(' ', '\t')
            val phrase = horizontalWhitespace.replace(match.value.lowercase(Locale.ROOT), " ")
            if (!removable(phrase, left, right)) {
                offset = match.range.last + 1
                continue
            }
            val tail = when {
                beginsSentence(left) -> right.trimStart(' ', '\t', ',', '.', '…', '!', '?')
                right.startsWith('…') || right.startsWith("...") -> right.trimStart(' ', '\t', '.', '…', ',')
                else -> right.trimStart(' ', '\t', ',')
            }
            // Keep a comma before a conjunction, for example "знаю, эм, что" → "знаю, что".
            val head = if (left.endsWith(',') && followingWord(tail) !in commaContinuations) left.dropLast(1) else left
            offset = head.length
            val space = if (head.isNotEmpty() && tail.isNotEmpty() && head.last() != '\n' &&
                tail.first() !in "\n.,!?;:…)]}") " " else ""
            remaining = head + space + tail
        }
    }

    private fun followingWord(text: String) = firstWord.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT).orEmpty()
    private fun previousWord(text: String) = lastWord.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT).orEmpty()
    private fun beginsSentence(text: String) = text.isEmpty() || text.last() in ".!?…\n"
    private fun isParenthetical(left: String, right: String) =
        (beginsSentence(left) || left.endsWith(',')) && right.startsWith(',')
}
