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
}
