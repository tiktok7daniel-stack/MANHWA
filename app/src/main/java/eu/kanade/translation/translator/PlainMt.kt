package eu.kanade.translation.translator

import eu.kanade.translation.live.OcrSanity

/**
 * MANHWA: what Google Translate and the offline ML Kit model get, made the most of. Neither has a stronger model to
 * pick, so the text they get is made easier: the voiced sounds every manhwa repeats (HUH, UGH, TSK...) are written
 * in Arabic directly, without being sent (Google spells them out or translates them as words), and a line the OCR
 * misread on decorative letters is mended first (one look-alike letter swap that gives an English word).
 */
object PlainMt {

    /** Voiced sounds and interjections, a whole bubble of them, in Arabic. */
    private val sounds = mapOf(
        "huh" to "هاه", "hah" to "هاه", "ha" to "ها", "haha" to "هاها", "hahaha" to "هاهاها", "heh" to "هه",
        "hehe" to "هيهي", "ugh" to "آه", "argh" to "آآه", "agh" to "آه", "ah" to "آه", "aah" to "آآه", "oh" to "أوه",
        "uh" to "أه", "um" to "امم", "hm" to "همم", "hmm" to "همم", "tsk" to "تسك", "tch" to "تش", "tut" to "تسك",
        "hmph" to "همف", "humph" to "همف", "huff" to "هاه", "hff" to "هاه", "pant" to "هاه", "gasp" to "هاه",
        "sigh" to "آه", "phew" to "فيو", "wow" to "واو", "whoa" to "واو", "oops" to "أوبس", "eh" to "إيه",
        "ow" to "آي", "ouch" to "آي", "eek" to "آآه", "hyuk" to "هئ", "heuk" to "هئ", "keugh" to "كح", "cough" to "كح",
        "kuh" to "كح", "sniff" to "شهق", "sob" to "هئ", "gulp" to "غلب", "yawn" to "هااه",
    )

    private val wrapped = Regex("^[\\s(*~\\[]*([A-Za-z]+(?:[\\s-]+[A-Za-z]+){0,2})[\\s)*~\\]]*([.!?…]*)[\\s)*~\\]]*$")

    /**
     * The Arabic of [text] when the whole bubble is a voiced sound ("HUFF...", "(TSK)", "UGH!", "HA HA"), with its
     * own punctuation; null for anything else.
     */
    fun sound(text: String): String? {
        val m = wrapped.matchEntire(text.trim()) ?: return null
        val words = m.groupValues[1].lowercase().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        val arabic = words.map { w -> sounds[w] ?: sounds[squeeze(w)] ?: return null }
        val marks = m.groupValues[2].replace('?', '؟').replace("…", "...")
        return arabic.joinToString(" ") + marks
    }

    /** "UGHHH" read as "ugh", "HUUUH" as "huh": a letter said longer is the same sound. */
    private fun squeeze(word: String): String {
        val out = StringBuilder()
        for (c in word) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    /**
     * MANHWA: short lines every manhwa repeats, with the Arabic a scanlation team writes for them. A whole bubble
     * that is one of these is written directly: Google and the offline model get these short lines wrong most
     * often ("I SEE" as "أنا أرى", "NO WAY" as "لا طريق"). Only lines with one clear meaning are here.
     */
    private val phrases = mapOf(
        "what" to "ماذا", "what the" to "ما هذا", "what the hell" to "ما هذا بحق الجحيم", "what the heck" to "ما هذا",
        "what is this" to "ما هذا", "what's this" to "ما هذا", "what happened" to "ماذا حدث",
        "what's going on" to "ما الذي يحدث", "what is going on" to "ما الذي يحدث", "what do you mean" to "ماذا تقصد",
        "what did you say" to "ماذا قلت", "what are you doing" to "ماذا تفعل", "what now" to "وماذا الآن",
        "what about you" to "وماذا عنك", "what's wrong" to "ما الخطب", "what's the matter" to "ما الأمر",
        "no way" to "مستحيل", "impossible" to "مستحيل", "it can't be" to "لا يمكن", "that can't be" to "لا يمكن",
        "this can't be" to "لا يمكن", "no" to "لا", "yes" to "نعم", "yeah" to "أجل", "yep" to "أجل", "nope" to "لا",
        "okay" to "حسنًا", "ok" to "حسنًا", "alright" to "حسنًا", "all right" to "حسنًا", "sure" to "بالتأكيد",
        "of course" to "بالطبع", "i see" to "فهمت", "i get it" to "فهمت", "got it" to "فهمت", "understood" to "مفهوم",
        "i understand" to "أفهم", "i don't understand" to "لا أفهم", "i don't know" to "لا أعرف", "i know" to "أعرف",
        "really" to "حقًا", "seriously" to "حقًا", "are you serious" to "هل أنت جاد", "for real" to "حقًا",
        "is that so" to "أهكذا إذن", "i'm sorry" to "أنا آسف", "sorry" to "آسف", "my bad" to "خطئي",
        "excuse me" to "عذرًا", "thank you" to "شكرًا لك", "thanks" to "شكرًا", "thank you so much" to "شكرًا جزيلًا",
        "you're welcome" to "على الرحب والسعة", "please" to "أرجوك", "help" to "النجدة", "help me" to "ساعدني",
        "save me" to "أنقذني", "run" to "اهرب", "run away" to "اهرب", "let's go" to "هيا بنا", "let's run" to "لنهرب",
        "come on" to "هيا", "hurry" to "أسرع", "hurry up" to "أسرع", "wait" to "انتظر", "wait a minute" to "انتظر لحظة",
        "wait a second" to "انتظر لحظة", "hold on" to "انتظر", "stop" to "توقف", "stop it" to "توقف عن هذا",
        "don't move" to "لا تتحرك", "don't come closer" to "لا تقترب", "stay back" to "ابقَ بعيدًا", "get back" to "تراجع",
        "watch out" to "احذر", "look out" to "احذر", "be careful" to "كن حذرًا", "careful" to "احذر",
        "shut up" to "اصمت", "be quiet" to "اهدأ", "calm down" to "اهدأ", "damn" to "تبًا", "damn it" to "تبًا",
        "dammit" to "تبًا", "shit" to "تبًا", "crap" to "تبًا", "you bastard" to "أيها الوغد", "bastard" to "وغد",
        "you fool" to "أيها الأحمق", "idiot" to "أحمق", "you idiot" to "أيها الأحمق", "who are you" to "من أنت",
        "who is it" to "من هناك", "who's there" to "من هناك", "where am i" to "أين أنا", "where are we" to "أين نحن",
        "where are you" to "أين أنت", "why" to "لماذا", "how come" to "كيف ذلك", "that's right" to "هذا صحيح",
        "exactly" to "بالضبط", "indeed" to "بالفعل", "i did it" to "لقد فعلتها", "we did it" to "لقد فعلناها",
        "it's over" to "انتهى الأمر", "finally" to "أخيرًا", "at last" to "أخيرًا", "again" to "مرة أخرى",
        "one more time" to "مرة أخرى", "not yet" to "ليس بعد", "not bad" to "ليس سيئًا", "great" to "رائع",
        "amazing" to "مذهل", "awesome" to "رائع", "incredible" to "لا يُصدَّق", "perfect" to "ممتاز",
        "good job" to "أحسنت", "well done" to "أحسنت", "congratulations" to "تهانينا", "good morning" to "صباح الخير",
        "good night" to "تصبح على خير", "goodbye" to "وداعًا", "bye" to "وداعًا", "see you" to "أراك لاحقًا",
        "see you later" to "أراك لاحقًا", "hello" to "مرحبًا", "hi" to "مرحبًا", "welcome" to "أهلًا بك",
        "long time no see" to "لم أرك منذ زمن", "i'll kill you" to "سأقتلك", "attack" to "هجوم", "retreat" to "انسحبوا",
        "it hurts" to "هذا مؤلم", "i'm fine" to "أنا بخير", "i'm okay" to "أنا بخير", "are you okay" to "هل أنت بخير",
        "are you alright" to "هل أنت بخير", "never mind" to "لا عليك", "nothing" to "لا شيء", "it's nothing" to "لا شيء",
        "no problem" to "لا مشكلة", "don't worry" to "لا تقلق", "trust me" to "ثق بي", "believe me" to "صدقني",
        "i promise" to "أعدك", "i love you" to "أحبك", "i hate you" to "أكرهك", "leave me alone" to "اتركني وشأني",
        "let me go" to "اتركني", "get out" to "اخرج", "get lost" to "اغرب عن وجهي", "go away" to "ابتعد",
        "come here" to "تعال إلى هنا", "follow me" to "اتبعني", "this way" to "من هنا", "over there" to "هناك",
        "over here" to "هنا", "listen" to "اسمع", "to be continued" to "يتبع", "the end" to "النهاية",
        "next episode" to "الحلقة القادمة", "next chapter" to "الفصل القادم", "meanwhile" to "في هذه الأثناء",
        "a few days later" to "بعد بضعة أيام", "the next day" to "في اليوم التالي", "that night" to "في تلك الليلة",
        "a moment later" to "بعد لحظة", "at that moment" to "في تلك اللحظة", "level up" to "ارتفع المستوى",
        "game over" to "انتهت اللعبة", "quest complete" to "اكتملت المهمة", "mission complete" to "اكتملت المهمة",
        "warning" to "تحذير", "your majesty" to "جلالتك", "your highness" to "سموّك", "everyone" to "الجميع",
    )

    private val phraseShape = Regex("^[\\s\"“(\\[]*(.+?)[\\s\"”)\\]]*([.!?…]*)[\\s\"”)\\]]*$")
    private val notWord = Regex("[^a-z' ]+")
    private val spaces = Regex("\\s+")

    /** The Arabic of [text] when the whole bubble is one of the known short lines, with its own punctuation. */
    fun phrase(text: String): String? {
        val m = phraseShape.matchEntire(text.trim().replace('\n', ' ')) ?: return null
        val key = m.groupValues[1].lowercase().replace('’', '\'').replace(notWord, " ").replace(spaces, " ").trim()
        val arabic = phrases[key] ?: return null
        return arabic + m.groupValues[2].replace('?', '؟').replace("…", "...")
    }

    /** A bubble written directly, without any engine: a voiced sound or a known short line. */
    fun direct(text: String): String? = sound(text) ?: phrase(text)

    /** Spoken English as lettered in comics, in the plain English the engines translate well. */
    private val spoken = listOf(
        "gonna" to "going to", "wanna" to "want to", "gotta" to "have to", "gimme" to "give me", "lemme" to "let me",
        "kinda" to "kind of", "sorta" to "sort of", "dunno" to "do not know", "outta" to "out of", "lotta" to "lot of",
        "ain't" to "is not", "y'all" to "you all", "ya'll" to "you all", "c'mon" to "come on", "'cause" to "because",
        "cuz" to "because", "coz" to "because", "'til" to "until", "tho" to "though", "thru" to "through",
        "whatcha" to "what are you", "gotcha" to "got you", "betcha" to "bet you", "d'you" to "do you",
        "shoulda" to "should have", "coulda" to "could have", "woulda" to "would have", "musta" to "must have",
        "tryna" to "trying to", "imma" to "I am going to", "i'mma" to "I am going to", "'em" to "them",
        "ol'" to "old", "lil'" to "little", "ya" to "you", "yer" to "your", "nah" to "no",
    ).map { (from, to) -> Regex("(?<![\\p{L}'])" + Regex.escape(from) + "(?![\\p{L}'])", RegexOption.IGNORE_CASE) to to }

    private val droppedG = Regex("(?<=\\p{L}{2})in'(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val stutter = Regex("(?<![\\p{L}])([A-Za-z])-(?=\\1)", RegexOption.IGNORE_CASE)

    /**
     * [text] in plain English: slang spelled out ("GONNA" as "going to"), the dropped g put back ("NOTHIN'"), a
     * stutter said once ("W-WHAT" as "WHAT"). The engines are trained on plain English and translate it far better.
     */
    fun plainEnglish(text: String): String {
        var out = stutter.replace(text, "")
        out = droppedG.replace(out, "ing")
        for ((pattern, to) in spoken) out = pattern.replace(out) { m -> if (m.value.all { !it.isLetter() || it.isUpperCase() }) to.uppercase() else to }
        return out
    }

    /**
     * [text] as it is sent to an engine: words the OCR misread on decorative letters mended (when the English word
     * list is loaded), then spoken English spelled out.
     */
    fun preEdit(text: String): String = plainEnglish(OcrSanity.repairIfLoaded(text))
}
