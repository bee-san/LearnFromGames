package io.github.beesan.lfg

import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.Frequency
import io.github.beesan.lfg.dict.FrequencyModes
import io.github.beesan.lfg.dict.FrequencyValue
import io.github.beesan.lfg.dict.Pronunciation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FrequencyModesTest {
    @Test
    fun theModeIsReadFromAnIndexAndCanBeMadeRanks() {
        val index = """{"title":"SoL","revision":"1","frequencyMode":"occurrence-based","format":3}"""
        assertEquals(FrequencyModes.OCCURRENCE, FrequencyModes.declared(index))
        assertNull(FrequencyModes.declared("""{"title":"JPDB"}"""))
        val ranks = FrequencyModes.asRanks(index)
        assertEquals(FrequencyModes.RANK, FrequencyModes.declared(ranks))
        assertEquals("""{"title":"SoL","revision":"1","frequencyMode":"rank-based","format":3}""", ranks)
    }

    @Test
    fun ranksStartAtOneAndFewAreFirst() {
        // A ranking: の first, then up and up.
        assertTrue(FrequencyModes.holdRanks((1..500).toList()))
        // A count: many words seen once.
        assertFalse(FrequencyModes.holdRanks(List(300) { 1 } + (2..200).toList()))
        // A count of frequent words only, which starts above 1.
        assertFalse(FrequencyModes.holdRanks(listOf(5400, 9000, 120)))
        // Hachidori's own fixture "Frequency occurrence mode": 1, 2, 9.
        assertFalse(FrequencyModes.holdRanks(listOf(1, 2, 9)))
        assertFalse(FrequencyModes.holdRanks(emptyList()))
    }

    @Test
    fun everyFormOfFigureIsReadFromTheBanks() {
        val zip = File.createTempFile("freq", ".zip")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("index.json"))
                out.write("""{"title":"T","frequencyMode":"occurrence-based"}""".toByteArray())
                out.putNextEntry(ZipEntry("term_meta_bank_1.json"))
                out.write(
                    """[["の","freq",1],["は","freq",{"value":3,"displayValue":"3"}],["日","freq",{"reading":"ひ","frequency":7}],
                       ["月","freq",{"reading":"つき","frequency":{"value":9}}],["橋","pitch",{"reading":"はし","pitches":[]}]]""".toByteArray(),
                )
                out.putNextEntry(ZipEntry("kanji_meta_bank_1.json"))
                out.write("""[["一","freq",12]]""".toByteArray())
                out.putNextEntry(ZipEntry("term_bank_1.json"))
                out.write("""[["の","","","",0,["of"],1,""]]""".toByteArray())
            }
            assertEquals(listOf(1, 3, 7, 9, 12), FrequencyModes.values(zip).sorted())
        } finally {
            zip.delete()
        }
    }

    @Test
    fun aCountTakesNoPartInAWordsAverageRank() {
        fun f(name: String, value: Int, occurrence: Boolean = false) = Frequency(name, listOf(FrequencyValue(value)), occurrence)
        val entry = Entry(expression = "食べる", frequencies = listOf(f("JPDB", 1000), f("Counts", 50_000, occurrence = true), f("BCCWJ", 1000)))
        assertEquals(1000, Pronunciation.frequencyRank(entry))
        assertNull(Pronunciation.frequencyRank(Entry(expression = "x", frequencies = listOf(f("Counts", 5, occurrence = true)))))
    }
}
