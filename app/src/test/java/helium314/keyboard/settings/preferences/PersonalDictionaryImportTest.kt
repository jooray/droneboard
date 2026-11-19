// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.settings.preferences.PersonalDictionaryImport.Entry
import helium314.keyboard.settings.preferences.PersonalDictionaryImport.Error
import helium314.keyboard.settings.preferences.PersonalDictionaryImport.Format
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** pure JVM: no Android classes, no Robolectric */
class PersonalDictionaryImportTest {
    private fun parse(text: String) = PersonalDictionaryImport.parse(text.toByteArray(Charsets.UTF_8))

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun plainWords() {
        val p = parse("Bratislava\nParalelná Polis\nžltý\n")
        assertEquals(Format.TEXT, p.format)
        assertNull(p.error)
        assertEquals(listOf(Entry("Bratislava", null), Entry("Paralelná Polis", null), Entry("žltý", null)), p.entries)
        assertEquals(0, p.invalidLines)
        assertEquals(0, p.duplicates)
    }

    @Test fun columns() {
        val p = parse("word\nlightning\ten\nParalelná Polis\tsk\tpp\nBednár\tsk\t\t200\nomw\t\tOn my way!\nx\t\t\t17\n")
        assertEquals(
            listOf(
                Entry("word", null), Entry("lightning", "en"), Entry("Paralelná Polis", "sk", "pp"),
                Entry("Bednár", "sk", null, 200), Entry("omw", null, "On my way!"), Entry("x", null, null, 17),
            ), p.entries
        )
        assertEquals(0, p.invalidLines)
    }

    @Test fun bomCrlfCommentsAndBlankLines() {
        val p = parse("﻿# my words\r\n\r\nahoj\tsk\r\n   \r\n  # indented comment\r\nnostr\r\n")
        assertEquals(Format.TEXT, p.format)
        assertEquals(listOf(Entry("ahoj", "sk"), Entry("nostr", null)), p.entries)
        assertEquals(0, p.invalidLines)
    }

    @Test fun hashWithTabIsAnEntry() {
        val p = parse("#bitcoin\n#bitcoin\t\n#nostr\tsk\n")
        assertEquals(listOf(Entry("#bitcoin", null), Entry("#nostr", "sk")), p.entries)
    }

    @Test fun badLinesAreSkippedAndCounted() {
        val long = "a".repeat(PersonalDictionaryImport.MAX_WORD_LENGTH + 1)
        val longest = "b".repeat(PersonalDictionaryImport.MAX_WORD_LENGTH)
        val p = parse(
            listOf(
                "good",
                "\tsk",                // no word
                long,                  // too long
                longest,               // just fits
                "freq\tsk\t\t0",       // frequency out of range
                "freq\tsk\t\t256",
                "freq\tsk\t\tabc",
                "many\tsk\ts\t10\textra",
                "locale\tnot a locale",
                "shortcut\tsk\t$long",
                "ctrl\u0007char",
                "fine\tsk\t\t255",
            ).joinToString("\n")
        )
        assertEquals(listOf(Entry("good", null), Entry(longest, null), Entry("fine", "sk", null, 255)), p.entries)
        assertEquals(9, p.invalidLines)
        assertNull(p.error)
    }

    @Test fun malformedUtf8LineIsSkipped() {
        val bytes = "ok\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()) + "also ok\n".toByteArray()
        val p = PersonalDictionaryImport.parse(bytes)
        assertEquals(listOf("ok", "also ok"), p.entries.map { it.word })
        assertEquals(1, p.invalidLines)
    }

    @Test fun duplicatesAreCountedOnce() {
        val p = parse("ahoj\nahoj\nahoj\tsk\nahoj\tsk\tx\nAhoj\nahoj\t\n")
        assertEquals(listOf(Entry("ahoj", null), Entry("ahoj", "sk"), Entry("Ahoj", null)), p.entries)
        assertEquals(3, p.duplicates)
    }

    @Test fun localesAreNormalised() {
        val p = parse("a\ten_US\nb\ten-US\nc\tsk\nd\tEN-us\ne\tSK\nf\tsr-Latn\ng\tsr__#Latn\nh\tes-419\ni\tzh-Hant-TW\n")
        assertEquals(
            listOf("en_US", "en_US", "sk", "en_US", "sk", "sr__#Latn", "sr__#Latn", "es_419", "zh_TW_#Hant"),
            p.entries.map { it.locale }
        )
        assertEquals(0, p.invalidLines)
        assertNull(PersonalDictionaryImport.localeOrNull(""))
        assertNull(PersonalDictionaryImport.localeOrNull("not a locale"))
        // the same word under two spellings of a locale is one entry
        assertEquals(1, parse("x\ten-US\nx\ten_US\n").entries.size)
    }

    @Test fun gboard() {
        val p = parse("# Gboard Dictionary version:1\n\npp\tParalelná Polis\tsk\n\tBednár\tsk\nomw\tOn my way!\ten-US\n\tnostr\t\n\tlightning\nbroken\n")
        assertEquals(Format.GBOARD, p.format)
        assertEquals(
            listOf(
                Entry("Paralelná Polis", "sk", "pp"), Entry("Bednár", "sk"), Entry("On my way!", "en_US", "omw"),
                Entry("nostr", null), Entry("lightning", null),
            ), p.entries
        )
        assertEquals(1, p.invalidLines)
    }

    @Test fun gboardVersion2WithBomAndCrlf() {
        val p = parse("﻿# Gboard Dictionary version:2\r\n\r\n\tjuraj\tsk\r\n#x\thashtag\t\r\n")
        assertEquals(Format.GBOARD, p.format)
        assertEquals(listOf(Entry("juraj", "sk"), Entry("hashtag", null, "#x")), p.entries)
    }

    @Test fun gboardZip() {
        val p = PersonalDictionaryImport.parse(zip("dictionary.txt" to "# Gboard Dictionary version:1\n\n\tnostr\tsk\n"))
        assertEquals(Format.GBOARD, p.format)
        assertEquals(listOf(Entry("nostr", "sk")), p.entries)
        // in a folder of the archive
        assertEquals(1, PersonalDictionaryImport.parse(zip("x/readme" to "hi", "x/dictionary.txt" to "# Gboard Dictionary version:1\n\tw\t\n")).entries.size)
    }

    @Test fun zipWithoutAWordList() {
        assertEquals(Error.NOT_A_WORD_LIST, PersonalDictionaryImport.parse(zip("other.txt" to "word")).error)
        // a damaged archive does not crash
        val broken = zip("dictionary.txt" to "word").copyOf(20)
        assertEquals(Error.NOT_A_WORD_LIST, PersonalDictionaryImport.parse(broken).error)
    }

    @Test fun droneboardJson() {
        val json = """[
 {"word": "Paralelná Polis", "shortcut": "pp", "frequency": 250, "locale": "sk"},
 {"word": "nostr", "frequency": 999},
 {"word": "color", "locale": "en_US", "frequency": 120},
 {"word": "odd", "locale": "some odd locale"},
 {"word": ""},
 {"shortcut": "x"},
 "text",
 {"word": "nostr"}
]"""
        val p = parse(json)
        assertEquals(Format.DRONEBOARD, p.format)
        assertEquals(
            listOf(
                Entry("Paralelná Polis", "sk", "pp", 250), Entry("nostr", null, null, 255),
                Entry("color", "en_US", null, 120), Entry("odd", "some odd locale"),
            ), p.entries
        )
        assertEquals(3, p.invalidLines)
        assertEquals(1, p.duplicates)
        // the backup zip of Droneboard contains the same file
        val fromZip = PersonalDictionaryImport.parse(zip("prefs" to "x", PersonalDictionaryBackup.FILE_NAME to json))
        assertEquals(p.entries, fromZip.entries)
    }

    @Test fun brokenJsonAndBinaryFiles() {
        assertEquals(Error.NOT_A_WORD_LIST, parse("[{\"word\": ").error)
        assertEquals(Error.NOT_A_WORD_LIST, PersonalDictionaryImport.parse(byteArrayOf(1, 0, 2, 0, 3)).error)
        assertEquals(Error.NOT_A_WORD_LIST, PersonalDictionaryImport.parse("word\n".toByteArray(Charsets.UTF_16LE)).error)
        val empty = PersonalDictionaryImport.parse(ByteArray(0))
        assertNull(empty.error)
        assertTrue(empty.entries.isEmpty())
    }

    @Test fun limits() {
        val tooBig = ByteArray(PersonalDictionaryImport.MAX_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(Error.TOO_LARGE, PersonalDictionaryImport.parse(tooBig).error)

        fun words(n: Int) = (1..n).joinToString("\n") { "w$it" }
        val atLimit = parse(words(PersonalDictionaryImport.MAX_ENTRIES))
        assertNull(atLimit.error)
        assertEquals(PersonalDictionaryImport.MAX_ENTRIES, atLimit.entries.size)
        val over = parse(words(PersonalDictionaryImport.MAX_ENTRIES + 1))
        assertEquals(Error.TOO_LARGE, over.error)
        assertTrue(over.entries.isEmpty())

        // a zip that unpacks to more than the limit
        val bomb = zip("dictionary.txt" to "a".repeat(PersonalDictionaryImport.MAX_BYTES + 10))
        assertTrue(bomb.size < PersonalDictionaryImport.MAX_BYTES)
        assertEquals(Error.TOO_LARGE, PersonalDictionaryImport.parse(bomb).error)
    }

    @Test fun readLimitedStopsAfterTheLimit() {
        assertEquals(11, PersonalDictionaryImport.readLimited(ByteArray(100).inputStream(), 10).size)
        assertEquals(5, PersonalDictionaryImport.readLimited(ByteArray(5).inputStream(), 10).size)
    }

    @Test fun planAddsOnlyWhatIsMissing() {
        val existing = setOf(
            PersonalDictionaryImport.key("ahoj", "sk"), PersonalDictionaryImport.key("nostr", null),
            PersonalDictionaryImport.key("empty", ""), // an empty locale in the provider is "all languages"
        )
        val entries = parse("ahoj\tsk\nahoj\nahoj\ten\nnostr\nNostr\nempty\n").entries
        val plan = PersonalDictionaryImport.plan(entries, existing)
        assertEquals(listOf(Entry("ahoj", null), Entry("ahoj", "en"), Entry("Nostr", null)), plan.toAdd)
        assertEquals(3, plan.existing)
        // importing the same file again adds nothing
        val after = existing + plan.toAdd.map { it.key }
        assertTrue(PersonalDictionaryImport.plan(entries, after).toAdd.isEmpty())
    }

    @Test fun exportRoundTrips() {
        val entries = listOf(
            Entry("Paralelná Polis", "sk", "pp", 250), Entry("nostr", null, null, 200), Entry("#bitcoin", null, null, 1),
            Entry("color", "en_US", "clr", 255), Entry("sr", "sr__#Latn", null, 17),
        )
        val text = PersonalDictionaryImport.toText(entries)
        assertTrue(text.startsWith("#"))
        val p = parse(text)
        assertEquals(Format.TEXT, p.format)
        assertEquals(entries, p.entries)
        assertEquals(0, p.invalidLines)
        // a missing frequency is written as the default, a tab inside a word cannot break the columns
        val odd = PersonalDictionaryImport.toText(listOf(Entry("a\tb", null), Entry("zero", "sk", null, 0)))
        assertEquals(listOf(Entry("a b", null, null, 250), Entry("zero", "sk", null, 1)), parse(odd).entries)
    }
}
