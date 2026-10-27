package eu.kanade.translation.speech

import eu.kanade.tachiyomi.novel.Role
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock

/**
 * MANHWA: who says a bubble, for reading a page aloud with a voice per speaker. Captions, narration and system
 * windows are the narrator; a line whose Arabic speaks of its speaker in the feminine ("أنا متعبة", "لست متأكدة") is a
 * woman's voice; other speech a man's. Pure, so it is tested on its own.
 */
object BubbleRoles {

    private val narration = setOf("narration", "caption", "system")

    // "I am …ة", "I am not …ة", "I was …ة": the speaker talks of herself in the feminine
    private val feminineSelf = Regex(
        "(?<!\\p{L})(?:أنا|انا|لستُ|لست|كنتُ|كنت|صرتُ|صرت|أصبحتُ|أصبحت)\\s+(?:[وف]?ال)?\\p{L}{2,}ة(?!\\p{L})",
    )
    private val feminineWords = listOf("أنا آسفة", "انا اسفة", "سعيدة", "متأكدة", "خائفة", "مستعدة", "متعبة", "جائعة", "وحيدة")

    fun roleOf(block: TranslationBlock): Role {
        if (block.kind.lowercase() in narration) return Role.NARRATOR
        val text = block.userText ?: block.translation
        if (feminineSelf.containsMatchIn(text) || feminineWords.any { text.contains(it) }) return Role.FEMALE
        return Role.MALE
    }

    /** The page's bubbles in reading order with who says each (sound effects are left out). */
    fun readingOrder(page: PageTranslation?): List<Pair<String, Role>> {
        val blocks = page?.blocks.orEmpty().filter { it.translation.isNotBlank() && !it.kind.equals("sfx", true) }
        if (blocks.isEmpty()) return emptyList()
        val rowHeight = blocks.map { it.height }.average().toFloat().coerceAtLeast(1f)
        return blocks.sortedWith(compareBy({ (it.y / rowHeight).toInt() }, { -it.x }))
            .map { (it.userText ?: it.translation).replace('\n', ' ').trim() to roleOf(it) }
    }
}
