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
                // A game window: in brackets, or its words in capitals ("QUEST", "LEVEL UP"), not a line that says "skill"
                source.trimStart().startsWith("[") || Regex("\\b(SYSTEM|QUEST|LEVEL UP|STATUS|SKILL)\\b").containsMatchIn(source) -> SYSTEM
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

    // MANHWA: the faces Arabic scanlation teams set their pages in, or free (OFL) look-alikes of the paid ones.
    // Added after the first ones so a choice saved before still means the same font.
    ALMARAI("المراعي / Almarai", true),
    BALOO("بالو / Baloo Bhaijaan", true),
    IBM_PLEX("آي بي إم / IBM Plex Arabic", true),
    VAZIRMATN("وزير / Vazirmatn", true),
    ALEXANDRIA("الإسكندرية / Alexandria", true),
    BEIRUTI("بيروتي / Beiruti", true),
    NOTO_SANS("نوتو سانس / Noto Sans Arabic", true),
    RUBIK("روبيك / Rubik", true),
    READEX("ريدكس / Readex Pro", true),
    NOTO_KUFI("نوتو كوفي / Noto Kufi", true),
    KUFAM("كوفام / Kufam", true),
    LEMONADA("ليمونادة / Lemonada", true),
    RAKKAS("رقّاص / Rakkas", true),
    MADA("مدى / Mada", true),
    HARMATTAN("هرمتان / Harmattan", true),
    MIRZA("ميرزا / Mirza", true),
    EL_MESSIRI("المسيري / El Messiri", true),
    ZAIN("زين / Zain", true),
    PLAYPEN("بلاي بن / Playpen Sans", true),
    JOMHURIA("جمهورية / Jomhuria", true),
    KATIBEH("كاتبة / Katibeh", true),
    BLAKA("بلاكا / Blaka", true),
    VIBES("فايبز / Vibes", true),
    AREF_RUQAA("رقعة / Aref Ruqaa", true),
    AMIRI("أميري / Amiri", true),
    LATEEF("لطيف / Lateef", true),
    SCHEHERAZADE("شهرزاد / Scheherazade", true),
    MARKAZI("مركزي / Markazi", true),
    HANDJET("هاندجت / Handjet", true),
    QAHIRI("قاهري / Qahiri", true),
    ;

    /** The bundled font of the added faces (one weight, drawn as is). */
    private val extraFont: Int? get() = EXTRA[this]

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
        else -> Families.extra(extraFont ?: R.font.ar_cairo)
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
        else -> (extraFont ?: R.font.ar_cairo) to 0
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

        private val extras = HashMap<Int, FontFamily>()
        fun extra(res: Int): FontFamily = extras.getOrPut(res) { FontFamily(Font(res, FontWeight.Bold)) }
    }

    companion object {
        private val EXTRA: Map<TranslationTypeface, Int> by lazy {
            mapOf(
                ALMARAI to R.font.ar_almarai,
                BALOO to R.font.ar_baloobhaijaan2,
                IBM_PLEX to R.font.ar_ibmplexsansarabic,
                VAZIRMATN to R.font.ar_vazirmatn,
                ALEXANDRIA to R.font.ar_alexandria,
                BEIRUTI to R.font.ar_beiruti,
                NOTO_SANS to R.font.ar_notosansarabic,
                RUBIK to R.font.ar_rubik,
                READEX to R.font.ar_readexpro,
                NOTO_KUFI to R.font.ar_notokufiarabic,
                KUFAM to R.font.ar_kufam,
                LEMONADA to R.font.ar_lemonada,
                RAKKAS to R.font.ar_rakkas,
                MADA to R.font.ar_mada,
                HARMATTAN to R.font.ar_harmattan,
                MIRZA to R.font.ar_mirza,
                EL_MESSIRI to R.font.ar_elmessiri,
                ZAIN to R.font.ar_zain,
                PLAYPEN to R.font.ar_playpensansarabic,
                JOMHURIA to R.font.ar_jomhuria,
                KATIBEH to R.font.ar_katibeh,
                BLAKA to R.font.ar_blaka,
                VIBES to R.font.ar_vibes,
                AREF_RUQAA to R.font.ar_arefruqaa,
                AMIRI to R.font.ar_amiri,
                LATEEF to R.font.ar_lateef,
                SCHEHERAZADE to R.font.ar_scheherazadenew,
                MARKAZI to R.font.ar_markazitext,
                HANDJET to R.font.ar_handjet,
                QAHIRI to R.font.ar_qahiri,
            )
        }

        fun fromPref(pref: Preference<Int>): TranslationTypeface = entries.getOrNull(pref.get()) ?: SMART
    }
}
