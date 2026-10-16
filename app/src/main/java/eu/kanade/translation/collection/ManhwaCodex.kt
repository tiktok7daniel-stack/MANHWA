package eu.kanade.translation.collection

/**
 * MANHWA master codex: the standard Arabic for the terms every manhwa genre repeats (system windows and hunter
 * ranks, murim sects and martial arts, nobility and magic towers). The terms found in a chapter go to the team
 * with the series glossary, so the same term reads the same in every manhwa from the first chapter, without
 * waiting for the AI to settle on one. The series glossary always wins over the codex.
 */
object ManhwaCodex {

    enum class Pack(val id: String) { SYSTEM("system"), MURIM("murim"), FANTASY("fantasy") }

    private val system = linkedMapOf(
        "Status Window" to "نافذة الحالة",
        "Quest" to "مهمة",
        "Daily Quest" to "المهمة اليومية",
        "Hidden Quest" to "مهمة خفية",
        "Main Quest" to "المهمة الرئيسية",
        "Passive Skill" to "مهارة سلبية",
        "Active Skill" to "مهارة نشطة",
        "Skill Cooldown" to "وقت انتظار المهارة",
        "Cooldown" to "وقت الانتظار",
        "Level Up" to "ارتفاع المستوى",
        "EXP" to "نقاط الخبرة",
        "Experience Points" to "نقاط الخبرة",
        "Stat Points" to "نقاط الإحصائيات",
        "Strength" to "القوة",
        "Agility" to "الرشاقة",
        "Intelligence" to "الذكاء",
        "Stamina" to "التحمل",
        "Inventory" to "المخزون",
        "Dungeon" to "الزنزانة",
        "Dungeon Break" to "انفجار الزنزانة",
        "Red Gate" to "البوابة الحمراء",
        "Hunter" to "صياد",
        "Hunter Association" to "جمعية الصيادين",
        "S-Rank Hunter" to "صياد من الرتبة S",
        "S-Rank" to "الرتبة S",
        "Guild" to "النقابة",
        "Guild Master" to "سيد النقابة",
        "Awakening" to "الاستيقاظ",
        "Awakened" to "مستيقظ",
        "Reawakening" to "الاستيقاظ الثاني",
        "Mana" to "المانا",
        "Mana Stone" to "حجر المانا",
        "Magic Beast" to "وحش سحري",
        "Boss Monster" to "الوحش الزعيم",
        "Necromancer" to "مستحضر الأرواح",
        "Shadow Soldier" to "جندي الظل",
        "Shadow Extraction" to "استخراج الظل",
        "Regression" to "العودة بالزمن",
        "Regressor" to "العائد بالزمن",
        "Constellation" to "الكوكبة",
        "Tutorial" to "البرنامج التعليمي",
        "Ranker" to "صاحب التصنيف",
    )

    private val murim = linkedMapOf(
        "Murim" to "الموريم",
        "Murim Alliance" to "تحالف الموريم",
        "Heavenly Demon" to "الشيطان السماوي",
        "Heavenly Demon Cult" to "طائفة الشيطان السماوي",
        "Demonic Cult" to "الطائفة الشيطانية",
        "Mount Hua Sect" to "طائفة جبل هوا",
        "Mount Hua" to "جبل هوا",
        "Shaolin" to "شاولين",
        "Wudang" to "وودانغ",
        "Orthodox Faction" to "الفصيل القويم",
        "Unorthodox Faction" to "الفصيل المنحرف",
        "Righteous Faction" to "فصيل الصالحين",
        "Sect" to "الطائفة",
        "Sect Leader" to "زعيم الطائفة",
        "Elder" to "الشيخ",
        "Disciple" to "التلميذ",
        "Senior Brother" to "الأخ الأكبر",
        "Junior Brother" to "الأخ الأصغر",
        "Young Master" to "السيد الشاب",
        "Dantian" to "الدانتيان",
        "Qi" to "التشي",
        "Internal Energy" to "الطاقة الداخلية",
        "Sword Qi" to "تشي السيف",
        "Sword Aura" to "هالة السيف",
        "Martial Arts" to "الفنون القتالية",
        "Martial Artist" to "فنان قتالي",
        "Cultivation" to "الزراعة الروحية",
        "Meridians" to "المسارات",
        "Swordsmanship" to "فن المبارزة",
        "Peak Master" to "سيد القمة",
        "Grandmaster" to "الأستاذ الأعظم",
        "Transcendent" to "المتسامي",
    )

    private val fantasy = linkedMapOf(
        "Emperor" to "الإمبراطور",
        "Empress" to "الإمبراطورة",
        "Crown Prince" to "ولي العهد",
        "Princess" to "الأميرة",
        "Prince" to "الأمير",
        "Archduke" to "الأرشيدوق",
        "Grand Duke" to "الدوق الأكبر",
        "Duke" to "الدوق",
        "Duchess" to "الدوقة",
        "Marquis" to "الماركيز",
        "Countess" to "الكونتيسة",
        "Viscount" to "الفيكونت",
        "Baron" to "البارون",
        "Knight" to "الفارس",
        "Knight Commander" to "قائد الفرسان",
        "Holy Knight" to "الفارس المقدس",
        "Holy Sword" to "السيف المقدس",
        "Demon King" to "ملك الشياطين",
        "Hero" to "البطل",
        "Saintess" to "القديسة",
        "Temple" to "المعبد",
        "Magic Tower" to "برج السحر",
        "Tower Master" to "سيد البرج",
        "Floor Master" to "سيد الطابق",
        "Grand Mage" to "الساحر الأعظم",
        "Archmage" to "رئيس السحرة",
        "Mage" to "ساحر",
        "Sword Master" to "سيد السيف",
        "Aura" to "الهالة",
        "Mana Heart" to "قلب المانا",
        "Academy" to "الأكاديمية",
        "Your Majesty" to "جلالتك",
        "Your Highness" to "سموك",
        "Your Grace" to "سيادتك",
        "My Lady" to "سيدتي",
        "My Lord" to "سيدي",
        "Villainess" to "الشريرة",
    )

    fun terms(pack: Pack): Map<String, String> = when (pack) {
        Pack.SYSTEM -> system
        Pack.MURIM -> murim
        Pack.FANTASY -> fantasy
    }

    fun size(pack: Pack): Int = terms(pack).size

    private val regexes = HashMap<String, Regex>()

    private fun regex(term: String) = synchronized(regexes) {
        regexes.getOrPut(term) { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    }

    /**
     * The codex terms of the enabled [packs] that appear in [text] (English source only, and only for an Arabic
     * target), longest first so "Mana Stone" wins over "Mana".
     */
    fun termsIn(text: String, packs: Set<String>): Map<String, String> {
        if (packs.isEmpty() || text.isBlank()) return emptyMap()
        val found = LinkedHashMap<String, String>()
        Pack.entries.filter { it.id in packs }
            .flatMap { terms(it).entries }
            .sortedByDescending { it.key.length }
            .forEach { (term, arabic) ->
                if (found.keys.none { it.contains(term, ignoreCase = true) } && regex(term).containsMatchIn(text)) found[term] = arabic
            }
        return found
    }
}
