package ly.anoma.hex

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ly.anoma.hex.keyboard.SpaceSwipeBehavior
import ly.anoma.hex.speech.*
import ly.anoma.hex.typing.LearningMode
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.updates.AppUpdates
import ly.anoma.hex.updates.UpdateCheckJob

private enum class Page(val title: Int) {
    Home(R.string.app_name), Dictation(R.string.settings_dictation), Keyboard(R.string.settings_keyboard), Privacy(R.string.settings_privacy),
    Language(R.string.settings_dictation_language), Layout(R.string.settings_keyboard_layout), Cleanup(R.string.settings_clean), Theme(R.string.settings_theme), CustomTheme(R.string.theme_custom),
    Learning(R.string.settings_learning), KeyboardHeight(R.string.settings_keyboard_height), SpaceSwipe(R.string.settings_space_cursor),
    CleanupModels(R.string.cleanup_models_title), Updates(R.string.settings_updates),
    Policy(R.string.settings_privacy_policy), Licences(R.string.settings_licences), Licence(R.string.settings_licences);
    val parent get() = when (this) {
        Language, Cleanup, CleanupModels -> Dictation
        Layout, Theme, Learning, KeyboardHeight, SpaceSwipe -> Keyboard
        CustomTheme -> Theme
        Policy, Licences -> Privacy
        Licence -> Licences
        else -> Home
    }
}

/** A dictation style's name, in the app and the keyboard's menu. */
internal val Style.label get() = when (this) {
    Style.Polished -> R.string.style_polished
    Style.Casual -> R.string.style_casual
    Style.Notes -> R.string.style_notes
    Style.Verbatim -> R.string.style_verbatim
}

/** Licence files the credits name, in the order they appear in assets/licenses. */
private fun licenceFiles(context: Context) =
    context.assets.list("licenses").orEmpty().filter { it != "credits.txt" }.sorted()

@Composable
internal fun HexSettings(setup: Setup, speech: SpeechPreferences, typing: TypingPreferences,
                         phase: SpeechPhase, transcript: String, liveSpeech: LiveSpeech?, voiceLevel: FloatState,
                         notice: String?, dictate: () -> Unit, cancel: () -> Unit, clear: () -> Unit, setupPage: (Int) -> Unit,
                         systemSettings: () -> Unit, updatesRequest: Int) {
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    var licence by rememberSaveable { mutableStateOf("") }
    var practice by remember { mutableStateOf("") }
    val context = LocalContext.current
    val appearance = remember(context) { Appearance(context) }
    // The activity and the IME write the same preferences.
    // Observe them even while trying the keyboard inside this page.
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val stores = listOf("speech", "typing").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(changed) }
        onDispose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(changed) } }
    }
    val automatic = remember(revision, LocalConfiguration.current) { speech.spoken }
    val selectedLanguage = remember(revision) { speech.language }
    val selectedLayout = remember(revision) { typing.layout }
    val style = remember(revision) { speech.style }
    val lowercase = remember(revision) { speech.lowercase }
    val endWithPeriod = remember(revision) { speech.endWithPeriod }
    val customVocabulary = remember(revision) { speech.customVocabulary }
    var vocabularyDraft by remember { mutableStateOf(speech.vocabularyTerms.joinToString("\n")) }
    var confirmLearningReset by rememberSaveable { mutableStateOf(false) }
    val autoCapitalize = remember(revision) { typing.autoCapitalize }
    val autocorrect = remember(revision) { typing.autocorrect }
    val spaceSwipeBehavior = remember(revision) { typing.spaceSwipeBehavior }
    val swearWords = remember(revision) { typing.suggestSwearWords }
    val spatial = remember(revision) { typing.spatial }
    val popups = remember(revision) { typing.popups }
    val haptics = remember(revision) { typing.haptics }
    val dockedHeight = remember(revision) { typing.dockedHeight }
    val learnTyping = remember(revision) { typing.learnTyping }
    val learnSwipe = remember(revision) { typing.learnSwipe }
    val learnContext = remember(revision) { typing.learnContext }
    var copied by remember(transcript) { mutableStateOf(false) }
    fun open(next: Page) {
        if (phase != SpeechPhase.Idle) cancel()
        if (next != Page.Learning) confirmLearningReset = false
        page = next
    }
    LaunchedEffect(updatesRequest) { if (updatesRequest > 0) open(Page.Updates) }
    BackHandler(page != Page.Home) { open(page.parent) }

    Box(Modifier.fillMaxSize().background(voiceColorResource(R.color.page)).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter) {
        key(page) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page == Page.Home) Image(painterResource(R.drawable.voice_mark), null, Modifier.size(27.dp),
                            colorFilter = ColorFilter.tint(voiceColorResource(R.color.floating_brand)))
                        else Box(Modifier.size(44.dp).clickable(role = Role.Button) { open(page.parent) },
                            contentAlignment = Alignment.CenterStart) {
                            Image(painterResource(R.drawable.ic_back), stringResource(R.string.back), Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(voiceColorResource(R.color.ink)))
                        }
                        if (page == Page.Home) Spacer(Modifier.width(10.dp))
                        Label(if (page == Page.Licence) licence else stringResource(page.title), size = 26, weight = FontWeight.SemiBold,
                            lines = 2, mod = Modifier.semantics { heading() })
                    }
                    if (page == Page.Home) {
                        val steps = listOf(setup.mic, setup.chosen).count { !it }
                        val status = if (steps == 0) stringResource(R.string.home_ready) else pluralStringResource(R.plurals.home_finish_setup, steps, steps)
                        Row((if (steps > 0) Modifier.clickable(role = Role.Button) { setupPage(1) } else Modifier)
                            .heightIn(min = if (steps > 0) 44.dp else 26.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (steps == 0) Image(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp),
                                colorFilter = ColorFilter.tint(voiceColorResource(R.color.quiet)))
                            Spacer(Modifier.width(4.dp)); Label(status, muted = true, size = 14)
                            if (steps > 0) Label("  ›", muted = true)
                        }
                    }
                }
                when (page) {
                    Page.Home -> {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(voiceColorResource(R.color.ink))
                            .height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).fillMaxHeight().clickable(phase != SpeechPhase.Transcribing, role = Role.Button) { dictate() },
                                contentAlignment = Alignment.Center) {
                                Label(stringResource(when (phase) {
                                    SpeechPhase.Idle -> R.string.home_dictate
                                    SpeechPhase.Transcribing -> R.string.home_transcribing
                                    else -> R.string.home_finish
                                }), inverted = true, weight = FontWeight.SemiBold)
                            }
                            if (phase != SpeechPhase.Idle) Box(Modifier.fillMaxHeight().widthIn(min = 76.dp)
                                .clickable(role = Role.Button) { cancel() }.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center) { Label(stringResource(R.string.home_cancel), inverted = true, size = 14) }
                        }
                        if (phase != SpeechPhase.Idle) DictationPreview(phase, liveSpeech, voiceLevel)
                        if (transcript.isNotEmpty()) Card {
                            SelectionContainer { Label(transcript, mod = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), lines = Int.MAX_VALUE) }
                            // iPhone Hex's Copy and Clear. Android 13 and later show their own copied preview.
                            Row(Modifier.padding(horizontal = 4.dp)) {
                                Action(stringResource(if (copied) R.string.home_copied else R.string.home_copy)) {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), transcript))
                                    copied = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                                }
                                Action(stringResource(R.string.home_clear), clear)
                            }
                        }
                        if (notice != null) Label(notice, muted = true, size = 14, lines = 3)
                        Card {
                            listOf(Page.Dictation, Page.Keyboard, Page.Updates, Page.Privacy).forEachIndexed { i, dest ->
                                if (i != 0) Rule()
                                Entry(stringResource(dest.title), action = { open(dest) })
                            }
                        }
                        Card {
                            Entry(stringResource(R.string.home_keyboard_settings), value = "↗", action = systemSettings)
                            Rule(); Entry(stringResource(R.string.home_run_setup), action = { setupPage(0) })
                        }
                    }
                    Page.Dictation -> {
                        Card {
                            Entry(stringResource(R.string.settings_clean), value = stringResource(style.label), action = { open(Page.Cleanup) })
                            Rule(); Entry(stringResource(R.string.cleanup_models_title),
                                value = speech.cleanupModel?.label ?: stringResource(R.string.cleanup_models_basic),
                                action = { open(Page.CleanupModels) })
                            Rule(); Toggle(stringResource(R.string.dictation_lowercase), stringResource(R.string.dictation_lowercase_detail),
                                lowercase) { speech.lowercase = it }
                            Rule(); Toggle(stringResource(R.string.dictation_end_with_period), stringResource(R.string.dictation_end_with_period_detail),
                                endWithPeriod) { speech.endWithPeriod = it }
                            Rule(); Entry(stringResource(R.string.settings_dictation_language), if (selectedLanguage == null) automatic.label else null,
                                selectedLanguage?.label ?: stringResource(R.string.settings_automatic), { open(Page.Language) })
                        }
                        Card {
                            Toggle(stringResource(R.string.dictation_vocabulary), stringResource(R.string.dictation_vocabulary_detail),
                                customVocabulary) { speech.customVocabulary = it }
                            if (customVocabulary) {
                                val ink = voiceColorResource(R.color.ink)
                                BasicTextField(value = vocabularyDraft, onValueChange = {
                                    vocabularyDraft = it; speech.vocabularyTerms = it.split('\n')
                                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                                    textStyle = TextStyle(color = ink, fontFamily = inter, fontSize = 16.sp),
                                    cursorBrush = SolidColor(ink), decorationBox = { field ->
                                        Box { if (vocabularyDraft.isEmpty()) Label(stringResource(R.string.dictation_vocabulary_hint), muted = true); field() }
                                    })
                            }
                        }
                        Card {
                            Entry(stringResource(R.string.model_title), stringResource(R.string.model_ready))
                        }
                    }
                    Page.Keyboard -> {
                        Card {
                            Entry(stringResource(R.string.settings_keyboard_layout),
                                if (selectedLayout == null) stringResource(R.string.settings_layout_follows, automatic.label) else null,
                                selectedLayout?.label ?: stringResource(R.string.settings_automatic), { open(Page.Layout) })
                            Rule(); Entry(stringResource(R.string.settings_keyboard_height),
                                value = stringResource(R.string.settings_keyboard_height_percent, dockedHeight),
                                action = { open(Page.KeyboardHeight) })
                            Rule(); Entry(stringResource(R.string.settings_theme), value = stringResource(appearance.theme.label),
                                action = { open(Page.Theme) })
                            Rule(); Entry(stringResource(R.string.settings_learning), action = { open(Page.Learning) })
                            Rule(); Toggle(stringResource(R.string.settings_auto_capitalisation), stringResource(R.string.settings_auto_capitalisation_detail),
                                autoCapitalize) { typing.autoCapitalize = it }
                            Rule(); Toggle(stringResource(R.string.settings_autocorrect), stringResource(R.string.settings_autocorrect_detail), autocorrect) { typing.autocorrect = it }
                            Rule(); Entry(stringResource(R.string.settings_space_cursor), stringResource(spaceSwipeBehavior.description),
                                action = { open(Page.SpaceSwipe) })
                            // Shown under Autocorrect while it is on, as on iPhone (it was alone on an Experiments page, on by default).
                            if (autocorrect) { Rule(); Toggle(stringResource(R.string.settings_spatial), stringResource(R.string.settings_spatial_detail), spatial) { typing.spatial = it } }
                            Rule(); Toggle(stringResource(R.string.settings_swear_words), stringResource(R.string.settings_swear_words_detail), swearWords) { typing.suggestSwearWords = it }
                            // The words the keyboard keeps and suggests are Android's own personal dictionary.
                            Rule(); Entry(stringResource(R.string.settings_personal_dictionary), stringResource(R.string.settings_personal_dictionary_detail), "↗",
                                { context.startActivity(Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS)) })
                            Rule(); Toggle(stringResource(R.string.settings_popups), stringResource(R.string.settings_popups_detail), popups) { typing.popups = it }
                            Rule(); Toggle(stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_detail), haptics) { typing.haptics = it }
                        }
                        Label(stringResource(R.string.settings_futo_credit), size = 14, muted = true, lines = 2)
                        val ink = voiceColorResource(R.color.ink)
                        Card {
                            BasicTextField(value = practice, onValueChange = { practice = it }, maxLines = 4,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textStyle = TextStyle(color = ink, fontFamily = inter, fontSize = 16.sp),
                                cursorBrush = SolidColor(ink), decorationBox = { field ->
                                    Box { if (practice.isEmpty()) Label(stringResource(R.string.settings_test_keyboard), muted = true); field() }
                                })
                        }
                    }
                    Page.Privacy -> {
                        // A statement, not rows that look tappable.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_lock), null, Modifier.size(15.dp), colorFilter = ColorFilter.tint(voiceColorResource(R.color.ink)))
                            Spacer(Modifier.width(8.dp)); Label(stringResource(R.string.settings_privacy_line), weight = FontWeight.Medium)
                        }
                        Label(stringResource(R.string.settings_privacy_detail), muted = true, size = 14, lines = Int.MAX_VALUE)
                        Card {
                            Entry(stringResource(R.string.settings_privacy_policy), action = { open(Page.Policy) })
                            Rule(); Entry(stringResource(R.string.settings_licences), action = { open(Page.Licences) })
                        }
                    }
                    Page.Cleanup -> Card {
                        listOf(Style.Polished, Style.Verbatim).forEachIndexed { i, it ->
                            if (i != 0) Rule()
                            Entry(stringResource(it.label), value = if (it == style) "✓" else "", action = { speech.style = it; open(Page.Dictation) })
                        }
                    }
                    Page.CleanupModels -> CleanupModelPicker(speech)
                    Page.Updates -> UpdatesSettings(updatesRequest)
                    Page.Theme -> AndroidView(modifier = Modifier.fillMaxWidth(), factory = { host ->
                        themePicker(host, appearance) { theme ->
                            if (theme == VoiceTheme.Custom) open(Page.CustomTheme)
                            else appearance.theme = theme
                        }
                    })
                    Page.Learning -> {
                        Card {
                            Toggle(stringResource(R.string.learning_typing), stringResource(R.string.learning_typing_detail),
                                learnTyping) { typing.learnTyping = it }
                            Rule()
                            Toggle(stringResource(R.string.learning_swipe), stringResource(R.string.learning_swipe_detail),
                                learnSwipe) { typing.learnSwipe = it }
                            Rule()
                            Toggle(stringResource(R.string.learning_context), stringResource(R.string.learning_context_detail),
                                learnContext, enabled = learnTyping || learnSwipe) { typing.learnContext = it }
                        }
                        Card {
                            if (confirmLearningReset) {
                                Entry(stringResource(R.string.learning_reset_detail))
                                Rule()
                                Entry(stringResource(R.string.learning_reset_confirm), value = "", action = {
                                    typing.clearLearning()
                                    confirmLearningReset = false
                                })
                                Rule()
                                Entry(stringResource(android.R.string.cancel), value = "", action = { confirmLearningReset = false })
                            } else Entry(stringResource(R.string.learning_reset), action = { confirmLearningReset = true })
                        }
                        LearningOverview(typing)
                    }
                    Page.CustomTheme -> AndroidView(modifier = Modifier.fillMaxWidth(), factory = { host ->
                        customThemeEditor(host, appearance.custom) { colors ->
                            open(Page.Theme)
                            appearance.saveCustom(colors)
                        }
                    })
                    Page.KeyboardHeight -> {
                        Label(stringResource(R.string.settings_keyboard_height_detail), size = 14, muted = true)
                        Card {
                            (TypingPreferences.DOCKED_HEIGHT_RANGE step 5).forEachIndexed { index, height ->
                                if (index != 0) Rule()
                                Entry(stringResource(R.string.settings_keyboard_height_percent, height),
                                    value = if (height == dockedHeight) "✓" else "",
                                    action = { typing.dockedHeight = height; open(Page.Keyboard) })
                            }
                        }
                    }
                    Page.SpaceSwipe -> Card {
                        SpaceSwipeBehavior.entries.forEachIndexed { index, behavior ->
                            if (index != 0) Rule()
                            Entry(stringResource(behavior.title), stringResource(behavior.description),
                                value = if (behavior == spaceSwipeBehavior) "✓" else "",
                                action = { typing.spaceSwipeBehavior = behavior; open(Page.Keyboard) })
                        }
                    }
                    Page.Language, Page.Layout -> Card {
                        val layout = page == Page.Layout
                        // Layouts and dictation languages are separate lists that happen to match today.
                        val choices: List<Triple<String?, Boolean, () -> Unit>> =
                            if (layout) (listOf(null) + Spoken.entries).map { Triple(it?.label, it == selectedLayout, { typing.layout = it }) }
                            else (listOf(null) + DictationLanguage.supported).map { Triple(it?.label, it == selectedLanguage, { speech.language = it }) }
                        choices.forEachIndexed { i, (label, chosen, pick) ->
                            if (i != 0) Rule()
                            Entry(label ?: stringResource(R.string.settings_automatic),
                                if (label != null) null else stringResource(if (layout) R.string.settings_layout_automatic_detail else R.string.dictation_language_automatic_detail),
                                if (chosen) "✓" else "", { pick(); open(page.parent) })
                        }
                    }
                    Page.Policy, Page.Licences, Page.Licence -> {
                        val russian = LocalConfiguration.current.locales[0].language == "ru"
                        val asset = when (page) {
                            Page.Policy -> if (russian) "privacy-policy-ru.txt" else "privacy-policy.txt"
                            Page.Licences -> "licenses/credits.txt"
                            else -> "licenses/$licence"
                        }
                        val text = remember(asset) { context.assets.open(asset).bufferedReader().use { it.readText() } }
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Document(text) } }
                        // The credits name these files; each opens here.
                        if (page == Page.Licences) Card {
                            licenceFiles(context).forEachIndexed { i, file ->
                                if (i != 0) Rule()
                                Entry(file, action = { licence = file; open(Page.Licence) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SpaceSwipeBehavior.title get() = when (this) {
    SpaceSwipeBehavior.LanguageAndCursor -> R.string.space_swipe_mixed
    SpaceSwipeBehavior.LanguageOnly -> R.string.space_swipe_language
    SpaceSwipeBehavior.CursorOnly -> R.string.space_swipe_cursor
    SpaceSwipeBehavior.Off -> R.string.space_swipe_off
}

private val SpaceSwipeBehavior.description get() = when (this) {
    SpaceSwipeBehavior.LanguageAndCursor -> R.string.space_swipe_mixed_detail
    SpaceSwipeBehavior.LanguageOnly -> R.string.space_swipe_language_detail
    SpaceSwipeBehavior.CursorOnly -> R.string.space_swipe_cursor_detail
    SpaceSwipeBehavior.Off -> R.string.space_swipe_off_detail
}

@Composable
private fun LearningOverview(typing: TypingPreferences) {
    var reports by remember(typing) { mutableStateOf(typing.learningReports()) }
    DisposableEffect(typing) {
        val stop = typing.observeLearning { reports = typing.learningReports() }
        reports = typing.learningReports()
        onDispose(stop)
    }
    var language by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(LearningMode.Tap) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(stringResource(R.string.learning_report), weight = FontWeight.SemiBold)
            if (reports.isEmpty()) {
                Label(stringResource(R.string.learning_report_empty), muted = true, size = 14, lines = Int.MAX_VALUE)
                return@Column
            }
            val languages = reports.map { it.language }.distinct()
            val activeLanguage = language.takeIf { it in languages } ?: languages.first()
            val profiles = reports.filter { it.language == activeLanguage }
            if (languages.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (code in languages) {
                        val label = Spoken.matching(code)?.label ?: code
                        Action(if (code == activeLanguage) "$label ✓" else label) { language = code; expanded = false }
                    }
                }
            } else {
                Label(Spoken.matching(activeLanguage)?.label ?: activeLanguage, muted = true, size = 14)
            }
            val selection = profiles.firstOrNull { it.mode == mode } ?: profiles.first()
            val maximum = profiles.maxOf { it.learned.words.size }.coerceAtLeast(1)
            for (profile in profiles) {
                val title = stringResource(if (profile.mode == LearningMode.Tap) R.string.learning_typing else R.string.learning_swipe)
                val chosen = profile.mode == selection.mode
                val count = profile.learned.words.size
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    mode = profile.mode
                    expanded = false
                }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label(if (chosen) "$title ✓" else title, size = 14, weight = FontWeight.Medium)
                        Label(count.toString(), muted = true, size = 14)
                    }
                    LearningBar(count.toFloat() / maximum)
                }
            }
            val active = selection.learned
            Rule()
            Label(stringResource(R.string.learning_report_contexts, active.contextCount), muted = true, size = 13, lines = 2)
            if (selection.mode == LearningMode.Swipe) {
                Label(stringResource(R.string.learning_report_corrections, active.correctionCount), muted = true, size = 13, lines = 2)
            }
            val words = remember(active) {
                active.words.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            }
            val maxWeight = words.first().value.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label(stringResource(R.string.learning_report_word), size = 13, weight = FontWeight.Medium)
                Label(stringResource(R.string.learning_report_weight), muted = true, size = 13)
            }
            for ((word, weight) in words.take(if (expanded) words.size else 8)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label(word, size = 15, mod = Modifier.weight(1f))
                        Label(weight.toString(), muted = true, size = 14)
                    }
                    LearningBar(weight.toFloat() / maxWeight)
                }
            }
            if (words.size > 8) Action(stringResource(if (expanded) R.string.learning_report_collapse else R.string.learning_report_all)) {
                expanded = !expanded
            }
        }
    }
}

@Composable
private fun LearningBar(fraction: Float) {
    val color = voiceColorResource(R.color.ink)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = .12f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color.copy(alpha = .6f)))
    }
}

@Composable private fun Label(text: String, size: Int = 16, weight: FontWeight = FontWeight.Normal,
                              muted: Boolean = false, inverted: Boolean = false, lines: Int = 1, mod: Modifier = Modifier) {
    BasicText(text, mod, style = TextStyle(fontFamily = inter, fontWeight = weight, fontSize = size.sp,
        lineHeight = (size + 5).sp, color = voiceColorResource(if (inverted) R.color.page else if (muted) R.color.quiet else R.color.ink)),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}
/** Plain text as paragraphs: hard-wrapped lines joined so they wrap to the screen, and a short first line
 * with no closing punctuation (the policy's section names) set as a heading. */
@Composable private fun Document(text: String) {
    for (block in text.trim().split(Regex("\\n\\s*\\n"))) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        val titled = lines.size > 1 && lines[0].length <= 40 && lines[0].last() !in ".,:;!?)"
        if (titled) Column { Label(lines[0], size = 15, weight = FontWeight.SemiBold); Label(reflow(lines.drop(1)), size = 14, lines = Int.MAX_VALUE) }
        else Label(reflow(lines), size = 14, lines = Int.MAX_VALUE)
    }
}
/** Joins a line to the next unless it ends a sentence or a list entry, or ends with an address. */
private fun reflow(lines: List<String>) = buildString {
    for ((i, line) in lines.withIndex()) {
        append(line)
        if (i == lines.lastIndex) break
        val ends = line.last() in ".:;!?)" || line.substringAfterLast(' ').startsWith("http")
        append(if (ends) "\n" else " ")
    }
}
@Composable private fun Action(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Label(text, weight = FontWeight.Medium, size = 15) }
}
@Composable private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(voiceColorResource(R.color.sheet)), content = content)
}

@Composable
private fun UpdatesSettings(updatesRequest: Int) {
    val context = LocalContext.current
    val status by AppUpdates.status.collectAsState()
    val version = remember(context) { AppUpdates.installedVersion(context) }
    var automatic by remember { mutableStateOf(UpdateCheckJob.enabled(context)) }
    var installerError by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(updatesRequest) { AppUpdates.check(context) }

    Card {
        Entry(stringResource(R.string.update_current_version), note = version)
        Rule()
        Toggle(stringResource(R.string.update_automatic), stringResource(R.string.update_automatic_detail), automatic) { enabled ->
            automatic = enabled
            UpdateCheckJob.setEnabled(context, enabled)
            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val release = status.release
            val label = when (status.phase) {
                AppUpdates.Phase.Idle -> stringResource(R.string.settings_updates)
                AppUpdates.Phase.Checking -> stringResource(R.string.update_checking)
                AppUpdates.Phase.Current -> stringResource(R.string.update_current)
                AppUpdates.Phase.Available -> stringResource(R.string.update_available, release?.version.toString())
                AppUpdates.Phase.Downloading -> stringResource(R.string.update_downloading, status.percent)
                AppUpdates.Phase.Ready -> stringResource(R.string.update_ready, release?.version.toString())
                AppUpdates.Phase.Failed -> stringResource(if (release == null) R.string.update_check_failed else R.string.update_download_failed)
            }
            Label(label, weight = FontWeight.SemiBold, lines = 2)
            if (status.phase == AppUpdates.Phase.Downloading) LearningBar(status.percent / 100f)
            if (installerError) Label(stringResource(R.string.update_installer_failed), muted = true, lines = 3)
            if (status.phase !in setOf(AppUpdates.Phase.Checking, AppUpdates.Phase.Downloading)) {
                if (release != null && status.phase == AppUpdates.Phase.Ready) {
                    Label(stringResource(R.string.update_install_detail), muted = true, size = 14, lines = 3)
                    Action(stringResource(R.string.update_install)) {
                        installerError = false
                        val intent = if (context.packageManager.canRequestPackageInstalls()) AppUpdates.installIntent(context)
                        else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        if (intent != null) installerError = runCatching { context.startActivity(intent) }.isFailure
                    }
                } else if (release != null) {
                    val size = Formatter.formatShortFileSize(context, release.size)
                    Action(stringResource(R.string.update_download, size)) { AppUpdates.download(context, release) }
                }
                Action(stringResource(R.string.update_check)) { installerError = false; AppUpdates.check(context) }
            }
        }
    }
}

@Composable
private fun CleanupModelPicker(speech: SpeechPreferences) {
    val context = LocalContext.current
    val downloads by CleanupModels.state.collectAsState()
    var selected by remember { mutableStateOf(speech.cleanupModel) }
    val memory = remember {
        val activity = checkNotNull(context.getSystemService(ActivityManager::class.java))
        val info = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        info.totalMem to activity.isLowRamDevice
    }
    val recommended = CleanupModel.recommended(memory.first / 1_073_741_824.0, memory.second)
    val busy = downloads.values.any { it.phase == CleanupModels.Phase.Downloading || it.phase == CleanupModels.Phase.Verifying }

    Label(stringResource(R.string.cleanup_models_device, Formatter.formatShortFileSize(context, memory.first),
        recommended?.label ?: stringResource(R.string.cleanup_models_basic)), muted = true, size = 14, lines = Int.MAX_VALUE)
    Label(stringResource(R.string.cleanup_models_detail), muted = true, size = 14, lines = Int.MAX_VALUE)

    Card {
        Entry(stringResource(R.string.cleanup_models_basic), stringResource(R.string.cleanup_models_basic_detail),
            value = if (selected == null) "✓" else "", action = {
                selected = null
                speech.cleanupModel = null
            })
    }
    for ((index, model) in CleanupModel.entries.withIndex()) {
        val installed = CleanupModels.ready(context, model)
        val download = downloads[model]
        val tier = when (model) {
            CleanupModel.Qwen -> R.string.cleanup_model_light
            CleanupModel.Gemma2 -> R.string.cleanup_model_balanced
            CleanupModel.Gemma4 -> R.string.cleanup_model_heavy
        }
        val phase = download?.phase ?: CleanupModels.Phase.Idle
        val waiting = phase == CleanupModels.Phase.Downloading || phase == CleanupModels.Phase.Verifying
        val fraction = ((download?.bytes ?: 0L) / model.size.toFloat()).coerceIn(0f, 1f)
        Card {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label(model.label, weight = FontWeight.SemiBold)
                    if (recommended == model) Label(stringResource(R.string.cleanup_model_recommended), size = 12)
                }
                Label(stringResource(R.string.cleanup_model_size, stringResource(tier),
                    Formatter.formatShortFileSize(context, model.size)), muted = true, size = 13, lines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(CleanupModel.entries.size) { bar ->
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(4.dp))
                            .background(voiceColorResource(R.color.ink).copy(alpha = if (bar <= index) .74f else .13f)))
                    }
                }
                if (waiting) {
                    Label(stringResource(if (phase == CleanupModels.Phase.Verifying) R.string.cleanup_model_verifying
                        else R.string.cleanup_model_progress, (fraction * 100).toInt()), muted = true, size = 13)
                } else if (phase == CleanupModels.Phase.Failed) {
                    Label(stringResource(R.string.cleanup_model_failed), muted = true, size = 13, lines = 3)
                }
            }
            Row(Modifier.padding(horizontal = 4.dp)) {
                when {
                    installed -> {
                        if (selected == model) {
                            Label(stringResource(R.string.cleanup_model_selected), mod = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), muted = true, size = 13)
                        } else Action(stringResource(R.string.cleanup_model_select)) {
                            selected = model
                            speech.cleanupModel = model
                        }
                        if (!busy) Action(stringResource(R.string.cleanup_model_delete)) {
                            CleanupModels.delete(context, model)
                            if (selected == model && !CleanupModels.ready(context, model)) {
                                selected = null
                                speech.cleanupModel = null
                            }
                        }
                    }
                    phase == CleanupModels.Phase.Downloading -> Action(stringResource(R.string.cleanup_model_pause)) {
                        CleanupModels.pause()
                    }
                    !busy -> Action(stringResource(if (phase == CleanupModels.Phase.Failed || phase == CleanupModels.Phase.Paused)
                        R.string.cleanup_model_resume else R.string.cleanup_model_download)) {
                        CleanupModels.download(context, model)
                    }
                }
            }
        }
    }
}

@Composable private fun DictationPreview(phase: SpeechPhase, liveSpeech: LiveSpeech?, level: FloatState) {
    Card {
        AndroidView(modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
            factory = ::VoiceWaveView, update = {
                it.showPhase(phase)
                it.push(level.floatValue)
            })
        if (liveSpeech != null) AndroidView(
            modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, bottom = 5.dp).heightIn(max = 148.dp),
            factory = ::LiveTranscriptView, update = {
                it.showPhase(phase)
                if (phase == SpeechPhase.Listening) it.pushLevel(level.floatValue)
                it.show(liveSpeech)
            })
    }
}
@Composable private fun Rule() {
    Spacer(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(voiceColorResource(R.color.rule)))
}
@Composable private fun RowScope.RowTitle(title: String, note: String?) {
    Column(Modifier.weight(1f)) {
        Label(title, weight = FontWeight.Medium)
        if (note != null) Label(note, size = 13, muted = true, lines = 2)
    }
}
@Composable private fun Entry(title: String, note: String? = null, value: String = "›", action: (() -> Unit)? = null) {
    val click = if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier
    Row(Modifier.fillMaxWidth().then(click).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RowTitle(title, note)
        if (action != null && value.isNotEmpty()) Label(value, muted = true, size = if (value == "›") 22 else 15)
    }
}
@Composable private fun Toggle(title: String, note: String, on: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = change)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RowTitle(title, note)
        Box(Modifier.size(38.dp, 23.dp).background(voiceColorResource(if (on) R.color.ink else R.color.modifier), RoundedCornerShape(50))
            .padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Spacer(Modifier.size(17.dp).background(voiceColorResource(if (on) R.color.page else R.color.key), RoundedCornerShape(50)))
        }
    }
}
