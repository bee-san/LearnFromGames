// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.Selectable
import io.github.beesan.lfg.core.SetupFlow
import io.github.beesan.lfg.core.SetupStep
import io.github.beesan.lfg.core.Shortcut
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.core.Tutorial
import io.github.beesan.lfg.dict.DefaultDictionaries
import io.github.beesan.lfg.ocr.TextClean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupTest {
    // ── the steps ────────────────────────────────────────────────────────────

    @Test
    fun touchIsNeverAskedAboutAControllersButton() {
        val steps = SetupFlow.steps(controller = false, padConnected = false, downloaded = true)
        assertEquals(
            listOf(SetupStep.LANGUAGE, SetupStep.READING, SetupStep.CONTROLLER, SetupStep.ANKI, SetupStep.WHERE, SetupStep.AI, SetupStep.IMPROVE, SetupStep.TUTORIAL),
            steps,
        )
    }

    @Test
    fun aControllerAlreadyThereIsNotConnectedAgain() {
        val built = SetupFlow.steps(controller = true, padConnected = true, downloaded = true)
        assertFalse(SetupStep.CONNECT in built)
        assertTrue(SetupStep.BUTTON in built)
        val none = SetupFlow.steps(controller = true, padConnected = false, downloaded = true)
        assertEquals(SetupStep.CONNECT, SetupFlow.next(SetupStep.CONTROLLER, none))
        assertEquals(SetupStep.BUTTON, SetupFlow.next(SetupStep.CONNECT, none))
    }

    @Test
    fun theDownloadsAreWaitedForOnlyWhenTheyAreNotIn() {
        assertTrue(SetupStep.DOWNLOAD in SetupFlow.steps(false, false, downloaded = false))
        assertFalse(SetupStep.DOWNLOAD in SetupFlow.steps(false, false, downloaded = true))
    }

    @Test
    fun aStepThatIsGoneMovesOnToTheNextThatIsThere() {
        // The controller connected while "Connect it" showed; the downloads ended while they were waited for.
        assertEquals(SetupStep.BUTTON, SetupFlow.settle(SetupStep.CONNECT, SetupFlow.steps(true, true, true)))
        assertEquals(SetupStep.TUTORIAL, SetupFlow.settle(SetupStep.DOWNLOAD, SetupFlow.steps(false, false, true)))
        val steps = SetupFlow.steps(true, false, false)
        for (step in steps) assertEquals(step, SetupFlow.settle(step, steps))
    }

    @Test
    fun backAndNextStopAtTheEnds() {
        val steps = SetupFlow.steps(false, false, true)
        assertNull(SetupFlow.previous(SetupStep.LANGUAGE, steps))
        assertEquals(SetupStep.TUTORIAL, SetupFlow.next(SetupStep.TUTORIAL, steps))
        assertEquals(SetupStep.CONTROLLER, SetupFlow.previous(SetupStep.ANKI, steps))
        assertEquals(SetupStep.LANGUAGE, SetupStep.of(""))
        assertEquals(SetupStep.AI, SetupStep.of("AI"))
    }

    // ── the shortcut ─────────────────────────────────────────────────────────

    @Test
    fun theNamedShortcutsAreWhatTheyAlwaysWere() {
        assertEquals(Shortcut.L3 to Shortcut.R3, Shortcut.keys(Prefs.SHORTCUT_L3_R3))
        assertEquals(Shortcut.SELECT to Shortcut.START, Shortcut.keys(Prefs.SHORTCUT_SELECT_START))
        assertEquals(Shortcut.L3 to Shortcut.L3, Shortcut.keys(Prefs.SHORTCUT_L3))
        assertEquals("L3 + R3", Shortcut.label(Prefs.SHORTCUT_L3_R3))
        assertEquals("Select", Shortcut.label(Prefs.SHORTCUT_SELECT))
        // Anything unreadable is the default, never no shortcut at all.
        assertEquals(Shortcut.L3 to Shortcut.R3, Shortcut.keys("keys:"))
        assertEquals(Shortcut.L3 to Shortcut.R3, Shortcut.keys("nonsense"))
    }

    @Test
    fun buttonsPressedAtSetUpAreSavedAndReadBack() {
        // A pair Settings has a name for is saved under it, in either order.
        assertEquals(Prefs.SHORTCUT_L3_R3, Shortcut.of(listOf(Shortcut.R3, Shortcut.L3)))
        assertEquals(Prefs.SHORTCUT_START, Shortcut.of(listOf(Shortcut.START)))
        val back = Shortcut.of(listOf(188))!!
        assertEquals(188 to 188, Shortcut.keys(back))
        assertEquals("Button 1", Shortcut.label(back))
        val pair = Shortcut.of(listOf(Shortcut.L1, Shortcut.SELECT))!!
        assertEquals(Shortcut.L1 to Shortcut.SELECT, Shortcut.keys(pair))
        assertEquals("L1 + Select", Shortcut.label(pair))
    }

    @Test
    fun lookupsOwnButtonsCannotBeTheShortcut() {
        assertNull(Shortcut.of(listOf(Shortcut.A)))
        assertNull(Shortcut.of(listOf(Shortcut.L1, Shortcut.B)))
        assertNull(Shortcut.of(emptyList()))
        assertNull(Shortcut.of(listOf(Shortcut.L1, Shortcut.R1, Shortcut.L3)))
        assertFalse(Shortcut.allowed(Shortcut.X))
        assertTrue(Shortcut.allowed(Shortcut.L3))
    }

    // ── the tutorial ─────────────────────────────────────────────────────────

    @Test
    fun aStepIsDoneByWhatHappensAfterItBegan() {
        val then = Tutorial.Seen(lookup = true, moves = 3, cards = 1)
        assertFalse(Tutorial.done(Tutorial.Step.MOVE, then, then.copy(moves = 4)))
        assertTrue(Tutorial.done(Tutorial.Step.MOVE, then, then.copy(moves = 5)))
        assertFalse(Tutorial.done(Tutorial.Step.CARD, then, then))
        assertTrue(Tutorial.done(Tutorial.Step.CARD, then, then.copy(cards = 2)))
        assertFalse(Tutorial.done(Tutorial.Step.CLOSE, then, then))
        assertTrue(Tutorial.done(Tutorial.Step.CLOSE, then, then.copy(lookup = false, closes = 1)))
        assertFalse(Tutorial.done(Tutorial.Step.PICK, then, then.copy(wheelUp = true)))
        assertFalse(Tutorial.done(Tutorial.Step.DONE, then, then))
    }

    @Test
    fun touchHasNoStepThatNeedsAButton() {
        val touch = Tutorial.steps(controller = false)
        assertEquals(listOf(Tutorial.Step.TAP, Tutorial.Step.TAP_CARD, Tutorial.Step.DONE), touch)
        for (step in touch) assertFalse("L3" in Tutorial.words(step, "L3 + R3", "A", "B").first)
        assertEquals(Tutorial.Step.DONE, Tutorial.steps(controller = true).last())
        assertEquals("Press Select", Tutorial.words(Tutorial.Step.OPEN, "Select", "A", "B").first)
    }

    // ── languages ────────────────────────────────────────────────────────────

    @Test
    fun everyLanguageHasItsPackAndItsPracticeWord() {
        // Run from app/: the packs are hoshidicts' own, which is where Hachidori's rules are.
        val packs = File("../third_party/hoshidicts/languages").listFiles { f -> f.isDirectory && !f.name.startsWith("_") }!!.map { it.name }.toSet()
        assertEquals(packs, Language.entries.map { it.tag }.toSet())
        for (language in Language.entries) {
            assertTrue(language.title, language.practiceWord in language.practice)
            assertTrue(language.title, File("../third_party/hoshidicts/languages/${language.tag}/transforms.json").isFile)
            assertTrue(language.title, DefaultDictionaries.of(language).isNotEmpty())
            assertTrue(language.title, DefaultDictionaries.bytes(language) > 0)
        }
        assertEquals(5, DefaultDictionaries.of(Language.JAPANESE).size)
        assertNotNull(DefaultDictionaries.byId("wty-es-en"))
        assertNotNull(DefaultDictionaries.byId("jitendex"))
        assertNull(DefaultDictionaries.byId("wty-xx-en"))
        assertTrue(Language.entries.take(Language.COMMON).contains(Language.SPANISH))
    }

    @Test
    fun aWordIsTakenWholeFromAnyLetterOfIt() {
        val line = "Ayer comimos en un restaurante pequeño."
        val at = line.indexOf("mimos")
        assertEquals(line.indexOf("comimos"), Selectable.wordStart(line, at))
        assertEquals(line.indexOf("comimos") + "comimos".length, Selectable.wordEnd(line, at))
        assertEquals(0, Selectable.wordStart(line, 2))
        // An accent written as its own mark is part of its word.
        val marked = "un café solo"
        assertEquals(marked.indexOf(" solo"), Selectable.wordEnd(marked, 3))
        assertEquals(3, Selectable.wordStart(marked, marked.indexOf('́')))
    }

    @Test
    fun aLineInAnotherLanguageIsKeptAndOnlyItsCursorGoes() {
        val english = TextLine("The children were running. ▼", Box(0, 0, 400, 30), emptyList())
        // As Japanese it is a menu's English and goes; as English it is the line.
        assertNull(TextClean.line(english))
        assertEquals("The children were running.", TextClean.line(english, japanese = false)?.text?.trim())
        assertNull(TextClean.line(TextLine("…… !?", Box(0, 0, 100, 30), emptyList()), japanese = false))
        val chinese = TextLine("我想先洗个澡。", Box(0, 0, 400, 30), emptyList())
        assertEquals("我想先洗个澡。", TextClean.line(chinese, japanese = false)?.text)
    }
}
