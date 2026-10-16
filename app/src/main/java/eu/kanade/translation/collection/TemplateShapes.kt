package eu.kanade.translation.collection

/**
 * MANHWA auto-template synthesizer. System lines repeat with a different name in brackets and a different
 * number: "[Fireball] has reached Lv. 3", "[Ice Spear] has reached Lv. 7". When the AI translates such a line,
 * its shape is learnt too: the bracketed name becomes a slot and the numbers become {nX}, on both sides
 * ("[{s}] has reached lv. {n0}" to "وصلت [{s}] إلى المستوى {n0}"). Once a shape came back twice with the same
 * translation, every line of that shape is written on the phone: the name in the slot is translated from the
 * series glossary, the codex or the collection, and the numbers are put back.
 */
object TemplateShapes {

    /** A line's shape: its collection key, and the name that sat in the brackets. */
    class Shape(val key: String, val inner: String, val keyed: TextKey.Keyed)

    /** The slot as it is written in the saved key and translation (survives the cleaning of [TextKey]). */
    private const val SLOT = "§s§"
    private const val PREFIX = "shape:"

    private val sourceBracket = Regex("[\\[【「〈<]([^\\]】」〉>\\n]{2,40})[\\]】」〉>]")
    private val targetBracket = Regex("[\\[【「〈<«]([^\\]】」〉>»\\n]{1,60})[\\]】」〉>»]")

    /** The one bracketed name of [text], when there is exactly one and it is a name (letters, no sentence). */
    private fun innerOf(text: String): MatchResult? {
        val all = sourceBracket.findAll(text).toList()
        if (all.size != 1) return null
        val inner = all.first().groupValues[1].trim()
        if (inner.none(Char::isLetter) || inner.count { it == ' ' } > 4 || inner.any { it in ".!?" }) return null
        return all.first()
    }

    /** The shape key and translation to save for a translated line, or null when it has no shape. */
    fun shapeOf(text: String, translation: String): Pair<String, String>? {
        val source = innerOf(text) ?: return null
        val targets = targetBracket.findAll(translation).toList()
        if (targets.size != 1) return null
        val target = targets.first()
        val sourceShape = text.replaceRange(source.groups[1]!!.range, SLOT)
        val translationShape = translation.replaceRange(target.groups[1]!!.range, SLOT)
        val keyed = TextKey.key(sourceShape)
        // The numbers must be found in the translation too, or the shape is not reusable
        val template = TextKey.templateOf(translationShape, keyed, emptyMap()) ?: return null
        return PREFIX + keyed.template to template
    }

    /** The shape key to look up for [text], or null when it has none. */
    fun lookupKey(text: String): Shape? {
        val source = innerOf(text) ?: return null
        val keyed = TextKey.key(text.replaceRange(source.groups[1]!!.range, SLOT))
        return Shape(PREFIX + keyed.template, source.groupValues[1].trim(), keyed)
    }

    /** A saved shape translation with this line's name and numbers put in. */
    fun fill(translation: String, shape: Shape, inner: String, arabic: Boolean): String? {
        val filled = TextKey.fill(translation, shape.keyed, emptyMap(), arabic) ?: return null
        return filled.replace(SLOT, inner)
    }
}
