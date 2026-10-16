package eu.kanade.translation.style

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.tachiyomi.R
import tachiyomi.core.common.preference.Preference

/** Kind of bubble, as told by the AI (or guessed): each kind gets its own typeface in Auto mode. */
enum class BubbleKind { SPEECH, SHOUT, THOUGHT, NARRATION, SYSTEM, SFX, ART;

    companion object {
        fun parse(value: String?): BubbleKind? = when (value?.trim()?.lowercase()) {
            "speech", "dialogue", "talk" -> SPEECH
            "shout", "scream", "yell", "battle" -> SHOUT
            "thought", "whisper", "inner" -> THOUGHT
            "narration", "caption", "narrator" -> NARRATION
            "system", "window", "status", "ui" -> SYSTEM
            "sfx", "sound", "effect" -> SFX
            "art", "spell", "skill", "sign" -> ART
            else -> null
        }

        /** When the AI gave no kind: a cheap guess from the original text and the translation. */
        fun guess(source: String, translation: String): BubbleKind {
            val letters = source.filter { it.isLetter() }
            return when {
                source.trimStart().startsWith("[") || Regex("(?i)\\b(system|quest|level up|status|skill)\\b").containsMatchIn(source) -> SYSTEM
                source.trimStart().startsWith("(") -> THOUGHT
                letters.length >= 3 && letters.all { !it.isLowerCase() } && source.count { it == '!' } >= 1 -> SHOUT
                translation.count { it == '!' } >= 2 -> SHOUT
                else -> SPEECH
            }
        }
    }
}

/** Typefaces for translated bubbles. Arabic families are OFL fonts bundled with the app. */
@OptIn(ExperimentalTextApi::class)
enum class TranslationTypeface(val label: String, val arabic: Boolean) {
    AUTO("تلقائي حسب الفقاعة / Auto", true),
    CAIRO("Cairo", true),
    LALEZAR("Lalezar", true),
    CHANGA("Changa", true),
    TAJAWAL("Tajawal", true),
    NOTO_NASKH("Noto Naskh", true),
    MARHEY("Marhey", true),
    REEM_KUFI("Reem Kufi", true),
    ANIME_ACE("Anime Ace", false),
    MANGA_MASTER("Manga Master BB", false),
    COMIC("Comic Font", false),
    SMART("متناسق: Cairo + Lalezar للصراخ / Consistent", true),
    ;

    /** The family for a bubble of [kind]; Auto picks a matching style for every kind. */
    fun family(kind: BubbleKind, latinOnly: Boolean = false): FontFamily = when (this) {
        AUTO -> if (latinOnly) {
            when (kind) {
                BubbleKind.SHOUT, BubbleKind.SFX -> MANGA_MASTER.family(kind)
                else -> ANIME_ACE.family(kind)
            }
        } else {
            when (kind) {
                BubbleKind.SPEECH -> CAIRO.family(kind)
                BubbleKind.SHOUT, BubbleKind.SFX -> LALEZAR.family(kind)
                BubbleKind.THOUGHT -> TAJAWAL.family(kind)
                BubbleKind.NARRATION -> NOTO_NASKH.family(kind)
                BubbleKind.SYSTEM, BubbleKind.ART -> CHANGA.family(kind)
            }
        }
        SMART -> when {
            latinOnly -> if (kind == BubbleKind.SHOUT || kind == BubbleKind.SFX) Families.mangaMaster else Families.animeAce
            kind == BubbleKind.SHOUT || kind == BubbleKind.SFX -> Families.lalezar
            else -> Families.cairo
        }
        CAIRO -> Families.cairo
        LALEZAR -> Families.lalezar
        CHANGA -> Families.changa
        TAJAWAL -> if (kind == BubbleKind.THOUGHT) Families.tajawalLight else Families.tajawalBold
        NOTO_NASKH -> Families.notoNaskh
        MARHEY -> Families.marhey
        REEM_KUFI -> Families.reemKufi
        ANIME_ACE -> Families.animeAce
        MANGA_MASTER -> Families.mangaMaster
        COMIC -> Families.comic
    }

    /**
     * The same choice as [family], as a font resource and weight for drawing straight on a page bitmap
     * (weight 0 = a static font with no weight axis).
     */
    fun androidFont(kind: BubbleKind, latinOnly: Boolean = false): Pair<Int, Int> = when (this) {
        AUTO -> if (latinOnly) {
            if (kind == BubbleKind.SHOUT || kind == BubbleKind.SFX) MANGA_MASTER.androidFont(kind) else ANIME_ACE.androidFont(kind)
        } else {
            when (kind) {
                BubbleKind.SPEECH -> CAIRO.androidFont(kind)
                BubbleKind.SHOUT, BubbleKind.SFX -> LALEZAR.androidFont(kind)
                BubbleKind.THOUGHT -> TAJAWAL.androidFont(kind)
                BubbleKind.NARRATION -> NOTO_NASKH.androidFont(kind)
                BubbleKind.SYSTEM, BubbleKind.ART -> CHANGA.androidFont(kind)
            }
        }
        SMART -> when {
            latinOnly -> if (kind == BubbleKind.SHOUT || kind == BubbleKind.SFX) MANGA_MASTER.androidFont(kind) else ANIME_ACE.androidFont(kind)
            kind == BubbleKind.SHOUT || kind == BubbleKind.SFX -> LALEZAR.androidFont(kind)
            else -> CAIRO.androidFont(kind)
        }
        CAIRO -> R.font.ar_cairo to 700
        LALEZAR -> R.font.ar_lalezar to 0
        CHANGA -> R.font.ar_changa to 600
        TAJAWAL -> (if (kind == BubbleKind.THOUGHT) R.font.ar_tajawal_light else R.font.ar_tajawal_bold) to 0
        NOTO_NASKH -> R.font.ar_noto_naskh to 600
        MARHEY -> R.font.ar_marhey to 600
        REEM_KUFI -> R.font.ar_reem_kufi to 600
        ANIME_ACE -> R.font.animeace to 0
        MANGA_MASTER -> R.font.manga_master_bb to 0
        COMIC -> R.font.comic_book to 0
    }

    private object Families {
        fun variable(res: Int, weight: Int) = FontFamily(
            Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight))),
        )

        val cairo = variable(R.font.ar_cairo, 700)
        val changa = variable(R.font.ar_changa, 600)
        val notoNaskh = variable(R.font.ar_noto_naskh, 600)
        val marhey = variable(R.font.ar_marhey, 600)
        val reemKufi = variable(R.font.ar_reem_kufi, 600)
        val lalezar = FontFamily(Font(R.font.ar_lalezar))
        val tajawalLight = FontFamily(Font(R.font.ar_tajawal_light, FontWeight.Light))
        val tajawalBold = FontFamily(Font(R.font.ar_tajawal_bold, FontWeight.Bold))
        val animeAce = FontFamily(Font(R.font.animeace, FontWeight.Bold))
        val mangaMaster = FontFamily(Font(R.font.manga_master_bb, FontWeight.Bold))
        val comic = FontFamily(Font(R.font.comic_book, FontWeight.Bold))
    }

    companion object {
        fun fromPref(pref: Preference<Int>): TranslationTypeface = entries.getOrNull(pref.get()) ?: SMART
    }
}
