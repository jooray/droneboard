// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * Droneboard: the pieces of a freshly copied text that people usually want on their own:
 * an e-mail address, a link, a one-time code. They are offered as separate chips next to the whole clip.
 */
object QuickClips {
    enum class Type { EMAIL, LINK, CODE }
    data class Clip(val type: Type, val text: String)

    private const val MAX_LENGTH = 32_000 // longer texts are not searched
    const val MAX_CLIPS = 3

    private val EMAIL = Regex("""[\w.+-]+@(?:[\w-]+\.)+[\w-]{2,}""")
    private val LINK = Regex("""https?://[^\s<>"']+""")
    // a one-time code: 4 to 8 digits, or 5 to 8 capital letters and digits with at least one digit
    private val CODE = Regex("""(?<![\w.,:/+-]|\d[ -])(?:\d{4,8}|(?=[A-Z0-9]{0,7}\d)[A-Z0-9]{5,8}|\d{3}[ -]\d{3})(?![\w/+-]|[.,:]\w|[ -]\d)""")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    /** distinct pieces in the order they occur; empty when [content] has none, or is nothing but one piece */
    fun extract(content: CharSequence): List<Clip> {
        if (content.length > MAX_LENGTH) return emptyList()
        val found = ArrayList<Pair<IntRange, Clip>>()
        fun overlaps(range: IntRange) = found.any { it.first.first <= range.last && range.first <= it.first.last }
        LINK.findAll(content).forEach { found.add(it.range to Clip(Type.LINK, it.value.trimEnd(*TRAILING))) }
        EMAIL.findAll(content).forEach { if (!overlaps(it.range)) found.add(it.range to Clip(Type.EMAIL, it.value.trimEnd('.'))) }
        CODE.findAll(content).forEach {
            if (!overlaps(it.range)) found.add(it.range to Clip(Type.CODE, it.value.filter { c -> c != ' ' && c != '-' }))
        }
        val clips = found.sortedBy { it.first.first }.map { it.second }.distinct()
        // codes hide in running text, and a text full of numbers has no code worth a chip
        val codes = clips.count { it.type == Type.CODE }
        val wanted = if (codes > 1) clips.filter { it.type != Type.CODE } else clips
        if (wanted.size == 1 && wanted[0].text == content.trim().toString()) return emptyList()
        return wanted.take(MAX_CLIPS)
    }

    /** short text for the chip */
    fun label(clip: Clip): String {
        val text = if (clip.type == Type.LINK) clip.text.substringAfter("://").removePrefix("www.").trimEnd('/') else clip.text
        return if (text.length > 24) text.take(22) + "…" else text
    }
}
