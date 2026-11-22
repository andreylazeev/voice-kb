package ly.anoma.hex.typing

import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed word and gesture samples. Recorded scores use the same dictionaries the APK ships. */
class GlideReplayTest {
    @Test fun replaysCommonWords() {
        val words = mapOf("ru" to russian, "en" to english)
        for ((language, entries) in words) {
            val decoder = decoder(language)
            for (split in listOf(false, true)) {
                val grid = keyboard(language, split)
                val results = entries.flatMapIndexed { index, word ->
                    val path = word.map { grid.centres.getValue(if (it == 'ё') 'е' else it) }
                    if (path.distinct().size < 2) emptyList() else (0..2).map { variant ->
                        val gesture = trace(path, Random(20261122 + index * 17 + variant), variant)
                        word to decoder.suggestions(gesture, grid)
                    }
                }
                val first = results.count { (w, guesses) -> guesses.firstOrNull() == w }
                val top = results.count { (w, guesses) -> w in guesses }
                println("$language split=$split: $first/${results.size} top-1, $top/${results.size} top-3")
                println(results.filter { it.first != it.second.firstOrNull() }.take(28))
                assertTrue("too many first-choice misses for $language, split=$split",
                    first.toDouble() / results.size >= if (language == "ru") .93 else .98)
                assertTrue("too many missing alternatives for $language, split=$split",
                    top.toDouble() / results.size >= if (language == "ru") .97 else .99)
            }
        }
    }

    @Test fun distinguishesGoFromToGoOnBothLayouts() {
        val decoder = decoder("ru")
        for (split in listOf(false, true)) {
            val grid = keyboard("ru", split)
            for (word in listOf("иди", "идти")) {
                val keys = word.map(grid.centres::getValue)
                val previous = if (word == "идти") "надо" else null
                for (variant in 0..2) {
                    val gesture = trace(keys, Random(2026 + variant), variant)
                    val guesses = decoder.suggestions(gesture, grid, previous)
                    val label = "$word split=$split variant=$variant, $guesses"
                    // The intentionally short U-turn may fall between д and л.
                    if (variant == 2) assertTrue(label, word in guesses)
                    else assertEquals(label, word, guesses.firstOrNull())
                }
            }
        }
    }

    @Test fun canSwipeAWordThatWasChosenDuringOrdinaryTyping() {
        val decoder = decoder("ru")
        val grid = keyboard("ru", split = false)
        val word = "зорбекс"
        val gesture = word.map(grid.centres::getValue)
        val learned = LearnedWords.Empty.record(word, null, null, explicit = true)
        assertTrue(word !in decoder.suggestions(gesture, grid))
        assertEquals(word, decoder.suggestions(gesture, grid, typed = learned).firstOrNull())
    }

    private fun decoder(language: String): GlideDecoder {
        val lexicon = File("src/main/assets/glide/$language.json").readText()
        val words = Regex(""""([^"]+)":\s*(\d+)""").findAll(lexicon)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val pairs = File("src/main/assets/glide/${language}_bigrams.txt").useLines { GlideDecoder.readPairs(it) }
        return GlideDecoder(words, pairs)
    }

    private fun keyboard(language: String, split: Boolean): KeyGrid {
        val rows = if (language == "ru") listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
            else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val widest = rows.maxOf(String::length)
        val points = buildMap {
            rows.forEachIndexed { y, row ->
                val left = (widest - row.length) / 2.0
                val breakBefore = (row.length / 2.0).roundToInt()
                row.forEachIndexed { col, char ->
                    val gap = if (split && col >= breakBefore) widest * .19 else 0.0
                    put(char, KeyPoint((left + col + .5 + gap) * 30, (y + .5) * 45))
                }
            }
        }
        return KeyGrid(points, 30.0, 45.0)
    }

    private fun trace(path: List<KeyPoint>, random: Random, variant: Int): List<KeyPoint> {
        val vertices = path.fold(mutableListOf<KeyPoint>()) { points, point ->
            if (points.lastOrNull() != point) points += point
            points
        }.map { p ->
            val x = if (variant == 0) 0.0 else (random.nextDouble() - .5) * (if (variant == 1) 12 else 22)
            val y = if (variant == 0) 0.0 else (random.nextDouble() - .5) * (if (variant == 1) 14 else 24)
            KeyPoint(p.x + x, p.y + y)
        }
        fun between(a: KeyPoint, b: KeyPoint, fraction: Double) =
            KeyPoint(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
        val pathPoints = buildList {
            add(vertices.first())
            for (i in 1 until vertices.lastIndex) {
                val peak = vertices[i]
                if (variant == 2) {
                    val a = vertices[i - 1]
                    val b = vertices[i + 1]
                    val arrival = between(peak, a, (12 / hypot(a.x - peak.x, a.y - peak.y)).coerceAtMost(.30))
                    val exit = between(peak, b, (12 / hypot(b.x - peak.x, b.y - peak.y)).coerceAtMost(.30))
                    add(arrival)
                    add(between(between(arrival, peak, .55), between(peak, exit, .55), .55))
                    add(exit)
                } else add(peak)
            }
            add(vertices.last())
        }
        return buildList {
            add(pathPoints.first())
            for ((a, b) in pathPoints.zipWithNext()) {
                val steps = (hypot(a.x - b.x, a.y - b.y) / 8).roundToInt().coerceAtLeast(1)
                for (step in 1..steps) add(between(a, b, step.toDouble() / steps))
            }
        }
    }

    private val russian = """
        иди идти идём идем идите пойди уйди ходи пройти пройтись жди ждать ждём ждать прости простить пройти прошло
        привет пока здорово здравствуйте добрый доброе утро вечер ночь спокойной спасибо пожалуйста благодарю извини
        да нет ага угу хорошо ладно конечно можно нельзя надо нужно пора хочу хочешь хочу могу смогу можешь надоело
        будет будет был была было были буду будь есть ем ели еду едем поеду поехали ехать ешь поешь пью пить попить
        я ты мы вы он она они это этот эти здесь там тут туда сюда где куда откуда оттуда почему зачем как когда кто
        что ничего всё все ещё еще уже тоже даже только очень больше меньше лучше хуже снова опять сейчас сегодня
        завтра вчера послезавтра неделю неделя месяц день время сколько быстро медленно долго рано поздно позже после
        возле перед около рядом далеко близко дома домой дом работа домой работать встреча встретимся увидимся увидел
        люблю нравится скучаю скучно отлично класс супер нормально понимаю понимаю поняла понял понятно наверное точно
        вроде кажется действительно совсем почти просто вообще короче кстати слушай смотри посмотри вспомни забыл
        позвони звонок напиши написать переписка сообщение голосовое голос диктовка телефон клавиатура раскладка экран
        внешний внутренний плавать плавающий широкий узкий высота ширина режим настройки обновление приложение установи
        проверить проверим проверка использую включи выключи исправь исправить сменить переключи поменять размер увеличить
        уменьшить обычный раздельный отдельный русский английский украинский цвет светлая тёмная темная системная
        город улица такси метро автобус машина билет поезд вокзал аэропорт магазин чай кофе вода хлеб молоко завтрак
        обед ужин счёт счет оплата деньги карта подарок письмо папка файл отправь отправить получи открыть закрыть
        открыть выключить включить подъезд подъехать объехать съезд съесть съел объявление всё-таки всётаки чё чо ща кириллица
        ёлка елка ёж еж брать брат сестра друг друзья дружба дружить человек люди вопрос ответ решение начало конец
        среда четверг пятница вторник понедельник суббота воскресенье проверить доволен напомни неужели известный качество
    """.trimIndent().split(Regex("""\s+""")).distinct().filter { it.length >= 2 && it.all(Char::isLetter) }

    private val english = """
        go get got gone good going coming come went wait will well would wood word work working world write right
        hello hi hey there their then than them they this that these those when where why what how please thanks
        thank sorry excuse welcome today tomorrow yesterday morning night now next later after before again always
        never maybe really very sure okay just only like love want need about could would should couldn't next
        text voice type typing swipe screen keyboard key microphone message messages explain working testing tested
        think thing think thought through though throw another together open close up down over inside outside
        change size height width floating split normal regular application offline settings please check fold folded
        foldable receive send call coffee water house home school train flight apple banana book read ready should
        bring both game player funny friend friends again meet meeting minutes probably exactly expected example
    """.trimIndent().split(Regex("""\s+""")).distinct().filter { it.length >= 2 && it.all(Char::isLetter) }
}
