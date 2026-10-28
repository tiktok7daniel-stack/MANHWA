package eu.kanade.translation.live

import android.content.Context
import java.io.DataInputStream

/**
 * MANHWA: tells a line the OCR misread on stylised lettering ("Nimgng flmlvs impti shord fokm", "Why miist ico")
 * from real English, so the bubble is read again from its image before it is translated, instead of the garbled
 * letters being passed on (and drawn) as they are.
 *
 * The English words are a Bloom filter in the app's assets (370 000 words in 430 KB, built by
 * tools/build_word_filter.py from a public-domain list): a word is known or, rarely (1 in 100), wrongly taken as
 * known; a real word is never taken as unknown.
 */
object OcrSanity {

    class WordFilter(private val bits: ByteArray, private val m: Long, private val k: Int) {
        fun has(word: String): Boolean {
            val h1 = fnv(word, 0L)
            val h2 = fnv(word, SEED2) or 1L
            for (i in 0 until k) {
                val idx = java.lang.Long.remainderUnsigned(h1 + i * h2, m)
                if ((bits[(idx ushr 3).toInt()].toInt() and (1 shl (idx and 7).toInt())) == 0) return false
            }
            return true
        }

        companion object {
            fun read(bytes: ByteArray): WordFilter? = runCatching {
                DataInputStream(bytes.inputStream()).use { input ->
                    val magic = ByteArray(4).also { input.readFully(it) }
                    if (String(magic) != "MWBF") return null
                    val m = input.readInt().toLong() and 0xFFFFFFFFL
                    val k = input.readInt()
                    val bits = ByteArray((m / 8).toInt()).also { input.readFully(it) }
                    WordFilter(bits, m, k)
                }
            }.getOrNull()

            private const val SEED2 = -0x61c8864680b583ebL

            /** FNV-1a 64 over the word's UTF-8 bytes, started from the offset basis xor [seed]. */
            fun fnv(word: String, seed: Long): Long {
                var h = -0x340d631b7bdddcdbL xor seed
                for (b in word.toByteArray(Charsets.UTF_8)) {
                    h = h xor (b.toLong() and 0xFF)
                    h *= 0x100000001b3L
                }
                return h
            }
        }
    }

    @Volatile private var filter: WordFilter? = null
    @Volatile private var tried = false

    private fun filter(context: Context): WordFilter? {
        if (!tried) {
            synchronized(this) {
                if (!tried) {
                    filter = runCatching { context.assets.open(ASSET).use { it.readBytes() } }.getOrNull()?.let(WordFilter::read)
                    tried = true
                }
            }
        }
        return filter
    }

    private val word = Regex("[A-Za-z][A-Za-z']{2,}")

    /**
     * Whether [text] (a Latin line) reads as garbled: at least two of its words, and at least half of them, are
     * not English. Names fail too, which only costs one more look at the bubble's image.
     */
    fun garbled(text: String, known: (String) -> Boolean): Boolean {
        val words = word.findAll(text).map { it.value.replace("'", "").lowercase() }.filter { it.length >= 3 }.toList()
        if (words.size < 2) return false
        val unknown = words.count { !known(it) }
        return unknown >= 2 && unknown * 2 >= words.size
    }

    /** [garbled] with the bundled English words; false when they cannot be read (nothing is re-read then). */
    fun garbled(context: Context, text: String): Boolean {
        val f = filter(context) ?: return false
        return garbled(text, f::has)
    }

    private const val ASSET = "english_words.bloom"
}
