package ly.anoma.hex.keyboard

import ly.anoma.hex.speech.DictationLanguage
import ly.anoma.hex.speech.SpeechOptions
import ly.anoma.hex.speech.SpokenText
import ly.anoma.hex.speech.Style
import ly.anoma.hex.typing.GlideDecoder
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceKbBehaviorTest {
    @Test fun floatingPanelResizesWidthAndHeightIndependently() {
        val panel = PanelSettings(KeyboardMode.Floating, width = 320f, rowHeight = 41f)
        val taller = panel.resize(horizontalDp = 0f, verticalDp = 40f, maxWidth = 500f, maxRowHeight = 72f)
        assertEquals(320f, taller.width)
        assertEquals(51f, taller.rowHeight)
        val wider = panel.resize(horizontalDp = 50f, verticalDp = 0f, maxWidth = 500f, maxRowHeight = 72f)
        assertEquals(370f, wider.width)
        assertEquals(41f, wider.rowHeight)
    }

    @Test fun floatingPanelStopsAtTheWidthNeededByItsLabels() {
        val panel = PanelSettings(KeyboardMode.Floating, width = 350f)
        assertEquals(292f, panel.resize(-120f, 0f, 450f, 72f, minWidth = 292f).width)
    }

    @Test fun foldDisplayClassificationUsesItsShortSideInBothOrientations() {
        assertEquals("cover", ScreenProfile.fromDisplay(1248, 1972, 2.625f, false).display)
        assertEquals("inner", ScreenProfile.fromDisplay(2448, 1848, 2.625f, true).display)
        assertEquals("inner", ScreenProfile.fromDisplay(1848, 2448, 2.625f, false).display)
    }

    @Test fun recognisesTheSameRussianGestureAcrossTheSplitKeyboardGap() {
        val centres = mapOf(
            'п' to KeyPoint(40.0, 40.0), 'р' to KeyPoint(150.0, 40.0),
            'и' to KeyPoint(35.0, 60.0), 'в' to KeyPoint(20.0, 40.0),
            'е' to KeyPoint(32.0, 20.0), 'т' to KeyPoint(160.0, 60.0),
            'о' to KeyPoint(166.0, 40.0),
        )
        val path = listOf(KeyPoint(41.0, 40.0), KeyPoint(147.0, 41.0), KeyPoint(33.0, 60.0),
            KeyPoint(21.0, 39.0), KeyPoint(33.0, 22.0), KeyPoint(159.0, 60.0))
        val decoder = GlideDecoder(mapOf("привет" to 150, "приворот" to 199))
        assertEquals("привет", decoder.suggestions(path, KeyGrid(centres, 14.0, 20.0)).first())
    }

    @Test fun unfoldedScreenDefaultsToSplitInEitherOrientation() {
        assertEquals(KeyboardMode.Standard, ScreenProfile(inner = false, landscape = false).defaultMode)
        assertEquals(KeyboardMode.Split, ScreenProfile(inner = true, landscape = false).defaultMode)
        assertEquals(KeyboardMode.Split, ScreenProfile(inner = true, landscape = true).defaultMode)
    }

    @Test fun removesRussianHesitationAndKeepsMeaningfulWords() {
        val options = SpeechOptions(Style.Polished, DictationLanguage.Russian, clean = true, translate = false)
        assertEquals("Это вроде работает.", SpokenText.forDictation("Э-э, это это вроде работает.", options))
        assertEquals("Нет нет, отмена.", SpokenText.forDictation("Нет нет, отмена.", options))
        assertEquals("Э-э, это это вроде работает.", SpokenText.forDictation("Э-э, это это вроде работает.",
            options.copy(style = Style.Verbatim)))
    }

    @Test fun cleansConversationalRussianThroughTheDictationFormatter() {
        val options = SpeechOptions(Style.Polished, DictationLanguage.Russian, clean = true, translate = false)
        val cases = listOf(
            "Ну, э, я, типа, завтра, в общем, отправлю файл." to "Я завтра отправлю файл.",
            "Ну, я типа завтра, в общем, отправлю файл." to "Я завтра отправлю файл.",
            "Я, я, ну, короче, сегодня, сегодня пришлю документ." to "Я сегодня пришлю документ.",
            "Я думаю, М, что встречу надо перенести." to "Я думаю, что встречу надо перенести.",
            "Я, как бы вот, хотел уточнить время встречи." to "Я хотел уточнить время встречи.",
            "Я ну короче завтра отправлю файл" to "Я завтра отправлю файл.",
            "Я короче хотел спросить" to "Я хотел спросить.",
            "Я типа завтра приду" to "Я завтра приду.",
            "Я как бы хотел уточнить дату" to "Я хотел уточнить дату.",
            "Я значит вчера приехал" to "Я вчера приехал.",
            "Я в общем отправлю файл" to "Я отправлю файл.",
            "Эм. Ну… В общем, я, я, я отправлю файл." to "Я отправлю файл.",
            "Я, эм… я отправлю файл?" to "Я отправлю файл?",
            "Я, я, я хотел бы уточнить" to "Я хотел бы уточнить.",
            "Я э-э хочу хочу хочу уточнить." to "Я хочу уточнить.",
            "П-п-привет. Я с-сегодня приду." to "Привет. Я сегодня приду.",
            "Я, ну, думаю, что получится." to "Я думаю, что получится.",
            "Я знаю, эм, что получится." to "Я знаю, что получится.",
            "Я, как бы, уже отправил файл." to "Я уже отправил файл.",
            "Я хотел, это самое, уточнить адрес." to "Я хотел уточнить адрес.",
            "Ну, я приду. Эм, когда начнём?" to "Я приду. Когда начнём?",
            "Ну, 20 минут хватит." to "20 минут хватит.",
            "Э-э… эм." to "",
            "Э, ну, эм" to "",
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, SpokenText.forDictation(raw, options))
            assertEquals(expected, SpokenText.forDictation(expected, options))
        }
    }

    @Test fun keepsRussianWordsThatCarryMeaning() {
        val options = SpeechOptions(Style.Polished, DictationLanguage.Russian, clean = true, translate = false)
        val cases = listOf(
            "Ну и что?", "Ну да, конечно.", "Ну что ты делаешь?", "Вот и всё.",
            "Это устройство типа C.", "Этот путь короче и удобнее.", "Это самое важное.",
            "Он короче на две строки.", "Он короче обычного.", "Он типа профессионал.",
            "Значит, надо отменить встречу.", "Это значит, что поезд ушёл.", "Он значит для меня очень много.",
            "В общем чате я отправил файл.", "Я сделал как бы ты ни просил.",
            "Мы в общем чате договорились.", "В общем случае это работает.", "В общем меню есть нужный пункт.",
            "Он назвал букву Э.", "Мы выбрали буквы А, М, Б.", "Ну и что, в общем чате есть устройство типа C.",
            "Он как бы не замечает меня.", "Мне страшно, очень очень страшно.",
            "Мне нужно чуть чуть подождать.",
            "Я сказал: «Ну, э, вот как бы так».\nОн ответил: \"Эм, нет нет\".",
        )
        for (raw in cases) {
            assertEquals(raw, raw, SpokenText.forDictation(raw, options))
        }
        val raw = "Ну, э, я, типа, завтра отправлю файл."
        assertEquals(raw, SpokenText.forDictation(raw, options.copy(style = Style.Verbatim)))
        assertEquals(raw, SpokenText.forDictation(raw, options.copy(clean = false)))
    }
}
