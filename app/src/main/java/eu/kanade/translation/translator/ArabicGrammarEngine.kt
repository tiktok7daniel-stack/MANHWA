package eu.kanade.translation.translator

/**
 * MANHWA Arabic grammar engine: numbers and what they count, written the way Arabic wants them.
 *
 * A template saved with "{n0} دقائق" works for 5 but not for 1, 2 or 11. Arabic agrees the counted noun with
 * the number:
 * - 1: the noun alone with "واحد/واحدة" after it (دقيقة واحدة, طابق واحد);
 * - 2: the dual alone (دقيقتان, or دقيقتين after a preposition; نقطتا خبرة when a noun follows it);
 * - 3 to 10: the plural (5 دقائق, 7 طوابق);
 * - 11 to 99: the singular, accusative (11 دقيقة, 25 طابقًا);
 * - 100, 1000 and up: the last part decides (100 نقطة, 103 دقائق, 120 طابقًا).
 * Any form of a known noun after a number is put right, so the same fix works for templates from the
 * collection and for what the AI writes. Numbers in Arabic-Indic digits stay in those digits.
 */
object ArabicGrammarEngine {

    /** One counted noun: its singular, dual (nominative and genitive), plural, accusative singular, and gender. */
    class Noun(
        val singular: String,
        val dualNom: String,
        val dualGen: String,
        val plural: String,
        val accusative: String,
        val feminine: Boolean,
    ) {
        val forms: Set<String> = setOf(singular, dualNom, dualGen, plural, accusative)
    }

    private fun fem(singular: String, plural: String): Noun {
        val stem = singular.removeSuffix("ة")
        return Noun(singular, stem + "تان", stem + "تين", plural, singular, feminine = true)
    }

    private fun masc(singular: String, dualStem: String, plural: String, accusative: String = singular + "ًا"): Noun =
        Noun(singular, dualStem + "ان", dualStem + "ين", plural, accusative, feminine = false)

    val nouns: List<Noun> = listOf(
        // Time
        fem("ثانية", "ثوانٍ"),
        fem("دقيقة", "دقائق"),
        fem("ساعة", "ساعات"),
        masc("يوم", "يوم", "أيام"),
        masc("أسبوع", "أسبوع", "أسابيع"),
        masc("شهر", "شهر", "أشهر"),
        fem("سنة", "سنوات"),
        masc("عام", "عام", "أعوام"),
        masc("قرن", "قرن", "قرون"),
        fem("ليلة", "ليالٍ"),
        fem("لحظة", "لحظات"),
        // Game and system
        masc("طابق", "طابق", "طوابق"),
        Noun("مستوى", "مستويان", "مستويين", "مستويات", "مستوى", feminine = false),
        fem("نقطة", "نقاط"),
        fem("مرة", "مرات"),
        fem("خطوة", "خطوات"),
        fem("مرحلة", "مراحل"),
        fem("دائرة", "دوائر"),
        fem("نجمة", "نجوم"),
        fem("طبقة", "طبقات"),
        fem("عملة", "عملات"),
        fem("قطعة", "قطع"),
        fem("مهارة", "مهارات"),
        fem("مهمة", "مهام"),
        fem("جرعة", "جرعات"),
        fem("ضربة", "ضربات"),
        fem("رصاصة", "رصاصات"),
        fem("بوابة", "بوابات"),
        fem("زنزانة", "زنزانات"),
        fem("غارة", "غارات"),
        fem("حلقة", "حلقات"),
        fem("صفحة", "صفحات"),
        fem("جولة", "جولات"),
        fem("فرصة", "فرص"),
        fem("محاولة", "محاولات"),
        masc("وحش", "وحش", "وحوش"),
        masc("جندي", "جندي", "جنود"),
        masc("لاعب", "لاعب", "لاعبين"),
        masc("صياد", "صياد", "صيادين"),
        masc("هجوم", "هجوم", "هجمات"),
        masc("عنصر", "عنصر", "عناصر"),
        masc("حجر", "حجر", "أحجار"),
        masc("فصل", "فصل", "فصول"),
        masc("لقب", "لقب", "ألقاب"),
        masc("سيف", "سيف", "سيوف"),
        masc("شخص", "شخص", "أشخاص"),
        masc("رجل", "رجل", "رجال"),
        masc("فارس", "فارس", "فرسان"),
        masc("تلميذ", "تلميذ", "تلاميذ"),
        masc("عضو", "عضو", "أعضاء"),
        masc("متر", "متر", "أمتار"),
        masc("كيلومتر", "كيلومتر", "كيلومترات"),
        masc("كيلوغرام", "كيلوغرام", "كيلوغرامات"),
        masc("سهم", "سهم", "أسهم"),
        masc("درع", "درع", "دروع"),
        masc("باب", "باب", "أبواب"),
        masc("دور", "دور", "أدوار"),
        masc("ظل", "ظل", "ظلال"),
        Noun("ألف", "ألفان", "ألفين", "آلاف", "ألفًا", feminine = false),
        Noun("مليون", "مليونان", "مليونين", "ملايين", "مليونًا", feminine = false),
    )

    private val byForm: Map<String, Noun> = buildMap { nouns.forEach { n -> n.forms.forEach { putIfAbsent(it, n) } } }

    /** Words that make the number after them genitive (after them the dual ends in ين). */
    private val genitiveBefore = setOf(
        "بعد", "قبل", "خلال", "منذ", "من", "في", "على", "إلى", "الى", "عن", "لمدة", "مدة", "حوالي", "نحو", "مع", "بـ", "لـ",
        "فوق", "تحت", "أكثر", "أقل", "بين", "طوال", "حتى",
    )

    /** Nouns that can follow a dual in a construct ("نقطتا خبرة"): the dual loses its final ن. */
    private val constructNext = setOf("خبرة", "مهارة", "صحة", "مانا", "قوة", "إحصائيات", "ذهب", "فضة", "طاقة", "تحمل", "حياة")

    private val numberThenWord = Regex("(?<![\\p{L}\\p{N}.,])([0-9٠-٩]+)\\s+([\\p{L}ًٌٍَُِّْ]+)")

    private fun western(digits: String) = buildString { digits.forEach { append(if (it in '٠'..'٩') '0' + (it - '٠') else it) } }

    /** Puts every known counted noun after a whole number in its right form. */
    fun fixCounts(text: String): String {
        if (text.none { it in '؀'..'ۿ' }) return text
        return numberThenWord.replace(text) { match ->
            val digits = match.groupValues[1]
            val word = match.groupValues[2]
            val noun = byForm[word] ?: return@replace match.value
            val n = western(digits).toLongOrNull() ?: return@replace match.value
            val before = text.substring(0, match.range.first).trimEnd().substringAfterLast(' ').trimStart('(', '[', '«')
            val genitive = before in genitiveBefore
            val after = text.substring(match.range.last + 1).trimStart().substringBefore(' ').trimEnd('.', '!', '?', '؟', '،', ',')
            when {
                n == 1L -> noun.singular + " " + if (noun.feminine) "واحدة" else "واحد"
                n == 2L -> {
                    val dual = if (genitive) noun.dualGen else noun.dualNom
                    if (after in constructNext) dual.removeSuffix("ن") else dual
                }
                else -> "$digits " + formFor(n, noun)
            }
        }
    }

    private fun formFor(n: Long, noun: Noun): String {
        val lastTwo = n % 100
        return when {
            n == 0L -> noun.singular
            n in 3..10 -> noun.plural
            n in 11..99 -> noun.accusative
            // From 100 on, the last part decides
            lastTwo == 0L || lastTwo == 1L || lastTwo == 2L -> noun.singular
            lastTwo in 3..10 -> noun.plural
            else -> noun.accusative
        }
    }
}
