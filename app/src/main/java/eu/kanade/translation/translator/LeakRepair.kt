package eu.kanade.translation.translator

/**
 * MANHWA leak repair: English words the AI left inside an Arabic translation, fixed on the phone before any
 * new call is made.
 * 1. A term of the glossary (series names and the master codex terms of the chapter): its Arabic is put in.
 * 2. A proper name (capitalised, not an everyday English word): written in Arabic letters by
 *    [ArabicTransliterator] and reported, so it joins the series glossary and is spelt the same from then on.
 * 3. Anything else (real English words the AI forgot) is left for the quality check, which sends the line back.
 * Abbreviations in capitals (HP, EXP, S-RANK) are kept: Arabic manhwa write them that way.
 */
object LeakRepair {

    private val properRun = Regex("[A-Z][a-zA-Z'’]*(?:[-\\s][A-Z][a-zA-Z'’]*)*")

    fun repair(translation: String, glossary: Map<String, String>, onNewName: (String, String) -> Unit = { _, _ -> }): String {
        if (translation.none { it in '؀'..'ۿ' } || translation.none { it in 'A'..'Z' || it in 'a'..'z' }) return translation
        val byLower = glossary.entries.associate { it.key.lowercase() to it.value }
        var out = translation
        // Glossary terms first, longest first, wherever they stand (any case)
        glossary.keys.filter { it.any(Char::isLetter) && it.length >= 2 }.sortedByDescending { it.length }.forEach { term ->
            val arabic = glossary[term]?.takeIf { v -> v.any { it in '؀'..'ۿ' } } ?: return@forEach
            val regex = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            out = regex.replace(out, arabic)
        }
        // Then capitalised names left in Latin letters
        out = properRun.replace(out) { match ->
            val name = match.value.trim()
            byLower[name.lowercase()]?.let { return@replace it }
            if (name.length < 3 || name.none(Char::isLowerCase)) return@replace match.value
            val words = name.split(' ', '-').filter { it.isNotEmpty() }
            if (words.any { it.lowercase() in COMMON }) return@replace match.value
            val arabic = ArabicTransliterator.transliterate(name.replace(' ', '-')).replace('-', if (' ' in name) ' ' else '-')
            onNewName(name, arabic)
            arabic
        }
        return out
    }

    /** Everyday English words: left in a translation they are a forgotten sentence, not a name. */
    private val COMMON = setOf(
        "the", "and", "you", "your", "yours", "what", "why", "who", "how", "when", "where", "which", "this", "that", "these",
        "those", "there", "here", "they", "them", "their", "then", "than", "with", "without", "from", "into", "onto", "over",
        "under", "about", "after", "before", "again", "just", "only", "still", "even", "ever", "never", "always", "sometimes",
        "now", "today", "tomorrow", "yesterday", "tonight", "yes", "yeah", "no", "not", "nope", "okay", "ok", "well", "oh",
        "ah", "hey", "huh", "wait", "stop", "come", "go", "going", "gone", "get", "got", "give", "take", "make", "made", "let",
        "lets", "let's", "look", "see", "seen", "saw", "watch", "listen", "hear", "heard", "say", "said", "tell", "told",
        "know", "knew", "think", "thought", "feel", "felt", "want", "need", "like", "love", "hate", "kill", "die", "dead",
        "death", "live", "life", "run", "fight", "help", "please", "sorry", "thanks", "thank", "sir", "madam", "master",
        "lord", "lady", "king", "queen", "prince", "princess", "brother", "sister", "father", "mother", "dad", "mom", "son",
        "daughter", "friend", "enemy", "man", "woman", "boy", "girl", "people", "person", "guy", "kid", "child", "old",
        "young", "new", "good", "bad", "great", "big", "small", "strong", "weak", "fast", "slow", "real", "really", "very",
        "much", "many", "more", "most", "less", "least", "some", "any", "all", "every", "each", "both", "other", "another",
        "same", "different", "right", "left", "first", "last", "next", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "hundred", "thousand", "time", "day", "night", "year", "years", "month", "week", "hour",
        "minute", "second", "level", "skill", "quest", "system", "status", "window", "power", "magic", "sword", "attack",
        "defense", "monster", "dungeon", "gate", "hunter", "guild", "rank", "warning", "error", "complete", "completed",
        "failed", "success", "reward", "penalty", "notice", "alert", "damn", "hell", "god", "shit", "fuck", "what's",
        "that's", "it's", "i'm", "you're", "don't", "can't", "won't", "isn't", "aren't", "wasn't", "didn't", "doesn't",
        "haven't", "hasn't", "i'll", "you'll", "we", "us", "our", "he", "him", "his", "she", "her", "hers", "it", "its",
        "me", "my", "mine", "is", "am", "are", "was", "were", "be", "been", "being", "do", "does", "did", "done", "have",
        "has", "had", "will", "would", "shall", "should", "can", "could", "may", "might", "must", "if", "but", "or", "so",
        "because", "since", "while", "until", "though", "although", "also", "too", "maybe", "perhaps", "sure", "of", "in",
        "on", "at", "to", "for", "by", "as", "an", "a", "i", "end", "start", "begin", "open", "close", "level up",
        "chapter", "episode", "season", "volume", "part", "prologue", "epilogue", "side", "story", "special",
    )
}
