package eu.kanade.translation.model

import kotlinx.serialization.Serializable

@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
) {
    /** Where the reader's bubble adjustments for this page are kept ("" = not saved). */
    @kotlinx.serialization.Transient
    var editKey: String = ""

    companion object {
        val EMPTY = PageTranslation()
    }
}

@Serializable
data class TranslationBlock(
    var text: String,
    var translation: String = "",
    var width: Float,
    var height: Float,
    var x: Float,
    var y: Float,
    var symHeight: Float,
    var symWidth: Float,
    val angle: Float,
    // Bubble background sampled from the page (ARGB, 0 = unknown) and a readable text colour on top of it
    var bgColor: Int = 0,
    var textColor: Int = 0xFF000000.toInt(),
    // Bubble kind from the AI: speech, shout, thought, narration, system or sfx ("" = unknown)
    var kind: String = "",
    // The speech bubble around the text, found from the page pixels (page pixels, 0 = none found)
    var bubbleX: Float = 0f,
    var bubbleY: Float = 0f,
    var bubbleW: Float = 0f,
    var bubbleH: Float = 0f,
    // Oval bubble (true) or a box such as a caption or a system window (false)
    var bubbleRound: Boolean = true,
    // The reader's own adjustments: move, box size and text size of this bubble
    var userDx: Float = 0f,
    var userDy: Float = 0f,
    var userScale: Float = 1f,
    var userFont: Float = 1f,
) {
    val hasBubble: Boolean get() = bubbleW > 0f && bubbleH > 0f

    val edited: Boolean get() = userDx != 0f || userDy != 0f || userScale != 1f || userFont != 1f

    /** Placed by the vision model from a rough box: snapped onto the real bubble before it is shown. */
    @kotlinx.serialization.Transient
    var needsSnap: Boolean = false
}
