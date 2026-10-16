package eu.kanade.translation.team

import eu.kanade.translation.translator.AiTranslator.Provider

/**
 * MANHWA key sites: every AI site a key can come from, with the protocol it speaks, its API server, the exact
 * page where its keys are made, and what it gives for free. Anything not listed is added as a custom site: its
 * server is found from the address the reader pastes ([TeamStore.resolveSite]).
 */
object KeySites {

    data class Site(
        val id: String,
        val name: String,
        val protocol: Provider,
        val baseUrl: String,
        val keysUrl: String,
        /** A model known to work there, used until the live list says better. */
        val defaultModel: String,
        val freeEn: String,
        val freeAr: String,
    )

    const val CUSTOM = "custom"

    val all: List<Site> = listOf(
        Site(
            "gemini", "Gemini", Provider.GEMINI, Provider.GEMINI.defaultBaseUrl, "https://aistudio.google.com/app/apikey", "gemini-2.5-flash",
            "Free tier with daily limits per model, reads images, fast and strong.",
            "طبقة مجانية بحد يومي لكل موديل، بيقرا الصور، سريع وقوي.",
        ),
        Site(
            "openrouter", "OpenRouter", Provider.OPENROUTER, Provider.OPENROUTER.defaultBaseUrl, "https://openrouter.ai/keys", "google/gemini-2.5-flash",
            "Hundreds of models behind one key; models ending in :free cost nothing (daily limits).",
            "مئات الموديلات بمفتاح واحد؛ الموديلات اللي آخرها :free مجانية (بحد يومي).",
        ),
        Site(
            "groq", "Groq", Provider.GROQ, Provider.GROQ.defaultBaseUrl, "https://console.groq.com/keys", "llama-3.3-70b-versatile",
            "Free tier, the fastest answers (Llama, Qwen, GPT-OSS).",
            "طبقة مجانية، أسرع ردود (Llama وQwen وGPT-OSS).",
        ),
        Site(
            "deepseek", "DeepSeek", Provider.DEEPSEEK, Provider.DEEPSEEK.defaultBaseUrl, "https://platform.deepseek.com/api_keys", "deepseek-chat",
            "Very cheap and very good at Chinese and Korean; small paid credit.",
            "رخيص جداً وممتاز في الصيني والكوري؛ محتاج رصيد بسيط.",
        ),
        Site(
            "claude", "Claude", Provider.CLAUDE, Provider.CLAUDE.defaultBaseUrl, "https://console.anthropic.com/settings/keys", "claude-sonnet-5-5",
            "Paid, the most natural translations; reads images.",
            "مدفوع، أطبع ترجمة؛ بيقرا الصور.",
        ),
        Site(
            "openai", "OpenAI", Provider.OPENAI_COMPATIBLE, Provider.OPENAI_COMPATIBLE.defaultBaseUrl, "https://platform.openai.com/api-keys", "gpt-4o-mini",
            "Paid; GPT models, reads images.",
            "مدفوع؛ موديلات GPT، بيقرا الصور.",
        ),
        Site(
            "nara", "NaraRouter", Provider.OPENAI_COMPATIBLE, "https://router.bynara.id/v1", "https://router.bynara.id/keys", "",
            "A router with many models behind one key, free models included.",
            "راوتر فيه موديلات كتير بمفتاح واحد، ومنها مجانية.",
        ),
        Site(
            "cerebras", "Cerebras", Provider.OPENAI_COMPATIBLE, "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai/", "llama-3.3-70b",
            "Free tier, extremely fast (Llama, Qwen, GPT-OSS).",
            "طبقة مجانية، سريع جداً (Llama وQwen وGPT-OSS).",
        ),
        Site(
            "sambanova", "SambaNova", Provider.OPENAI_COMPATIBLE, "https://api.sambanova.ai/v1", "https://cloud.sambanova.ai/apis", "Meta-Llama-3.3-70B-Instruct",
            "Free tier, fast big models (Llama 70B, DeepSeek).",
            "طبقة مجانية، موديلات كبيرة وسريعة (Llama 70B وDeepSeek).",
        ),
        Site(
            "mistral", "Mistral", Provider.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys/", "mistral-small-latest",
            "Free experiment plan with rate limits; good in French and Arabic.",
            "خطة تجربة مجانية بحدود؛ كويس في الفرنساوي والعربي.",
        ),
        Site(
            "together", "Together AI", Provider.OPENAI_COMPATIBLE, "https://api.together.xyz/v1", "https://api.together.xyz/settings/api-keys", "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "Starting credit and some free models.",
            "رصيد بداية وبعض الموديلات المجانية.",
        ),
        Site(
            "github", "GitHub Models", Provider.OPENAI_COMPATIBLE, "https://models.github.ai/inference", "https://github.com/settings/tokens", "openai/gpt-4.1-mini",
            "Free with a GitHub token (models permission), daily limits.",
            "مجاني بتوكن GitHub (صلاحية models)، بحد يومي.",
        ),
        Site(
            "cohere", "Cohere", Provider.OPENAI_COMPATIBLE, "https://api.cohere.ai/compatibility/v1", "https://dashboard.cohere.com/api-keys", "command-a-03-2025",
            "Free trial key with monthly limits; strong in Arabic.",
            "مفتاح تجربة مجاني بحد شهري؛ قوي في العربي.",
        ),
        Site(
            "chutes", "Chutes", Provider.OPENAI_COMPATIBLE, "https://llm.chutes.ai/v1", "https://chutes.ai/app/api", "deepseek-ai/DeepSeek-V3",
            "Open models (DeepSeek, Qwen) with a free or very cheap quota.",
            "موديلات مفتوحة (DeepSeek وQwen) بحصة مجانية أو رخيصة جداً.",
        ),
        Site(
            "siliconflow", "SiliconFlow", Provider.OPENAI_COMPATIBLE, "https://api.siliconflow.com/v1", "https://cloud.siliconflow.com/account/ak", "deepseek-ai/DeepSeek-V3",
            "Some free models and a starting credit.",
            "بعض الموديلات المجانية ورصيد بداية.",
        ),
        Site(
            "fireworks", "Fireworks", Provider.OPENAI_COMPATIBLE, "https://api.fireworks.ai/inference/v1", "https://fireworks.ai/account/api-keys", "accounts/fireworks/models/llama-v3p3-70b-instruct",
            "Starting credit, fast open models.",
            "رصيد بداية، موديلات مفتوحة سريعة.",
        ),
    )

    fun byId(id: String?): Site? = all.firstOrNull { it.id == id }

    /** The listed site a server address belongs to, if any. */
    fun byUrl(url: String): Site? {
        val host = hostOf(url) ?: return null
        return all.firstOrNull { site -> hostOf(site.baseUrl) == host || hostOf(site.keysUrl) == host }
    }

    /** The listed site a key belongs to, from how it looks (same rules as before, plus a few more). */
    fun forKey(key: String): Site? {
        val k = key.trim()
        return when {
            k.startsWith("sk-ant") -> byId("claude")
            k.startsWith("gsk_") -> byId("groq")
            k.startsWith("sk-or") -> byId("openrouter")
            k.startsWith("csk-") -> byId("cerebras")
            k.startsWith("github_pat_") || k.startsWith("ghp_") -> byId("github")
            k.startsWith("AIza") -> byId("gemini")
            k.startsWith("sk-") -> byId("openai")
            else -> null
        }
    }

    fun hostOf(url: String): String? = runCatching {
        val withScheme = if ("://" in url) url else "https://$url"
        java.net.URI(withScheme.trim()).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()

    /** Server addresses to try for what the reader pasted, most likely first. */
    fun candidates(input: String): List<String> {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return emptyList()
        if ("://" !in url) url = "https://$url"
        // A page of the site, not its API: back to the root it was taken from
        listOf("/chat/completions", "/completions", "/messages", "/models", "/keys", "/api-keys", "/settings/keys", "/dashboard").forEach { tail ->
            if (url.endsWith(tail, ignoreCase = true)) url = url.dropLast(tail.length).trimEnd('/')
        }
        val root = runCatching { java.net.URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault(url)
        val list = mutableListOf(url)
        if (!Regex("/v\\d+[a-z]*$").containsMatchIn(url)) list += listOf("$url/v1", "$url/api/v1", "$url/openai/v1")
        list += listOf("$root/v1", "$root/api/v1", "$root/openai/v1", "$root/api/openai/v1")
        // api.<domain> is where many sites keep their API
        val host = hostOf(root)
        if (host != null && !host.startsWith("api.")) list += listOf("https://api.$host/v1")
        return list.distinct()
    }

    /**
     * The model to use from [models] (a key's live list): the newest Gemini Flash for Gemini, else the site's
     * known good model when it is there, else the best translation model found by name.
     */
    fun bestModel(models: List<String>, protocol: Provider, siteDefault: String): String? {
        if (models.isEmpty()) return null
        if (protocol == Provider.GEMINI) {
            val flash = models.mapNotNull { id ->
                Regex("^gemini-(\\d+(?:\\.\\d+)?)-flash$").find(id)?.groupValues?.get(1)?.toFloatOrNull()?.let { it to id }
            }.maxByOrNull { it.first }?.second
            return flash ?: models.firstOrNull { it == "gemini-flash-latest" } ?: models.firstOrNull { it == siteDefault }
        }
        if (siteDefault.isNotBlank() && siteDefault in models) return siteDefault
        val usable = models.filterNot { id -> SKIP.any { it in id.lowercase() } }.ifEmpty { return null }
        return usable.minWithOrNull(compareBy<String>({ rank(it) }, { it.length }))
    }

    private val SKIP = listOf(
        "embed", "tts", "whisper", "transcribe", "audio", "image", "dall-e", "moderation", "guard", "rerank", "realtime",
        "search", "ocr", "speech", "vision-preview", "-instruct-v0", "babbage", "davinci",
    )

    /** Families that translate manhwa well, best first; anything else comes after them. */
    private val FAMILIES = listOf(
        "deepseek-chat", "deepseek-v3", "gemini-2.5-flash", "gemini", "gpt-4.1", "gpt-4o", "claude", "qwen3", "qwen2.5-72b",
        "llama-3.3-70b", "llama-4", "gpt-oss-120b", "kimi", "glm-4", "mistral-large", "command-a", "mistral-small", "llama-3.1-70b",
        "qwen", "llama", "mistral",
    )

    private fun rank(id: String): Int {
        val m = id.lowercase()
        val free = if (m.endsWith(":free")) 0 else 1
        val family = FAMILIES.indexOfFirst { it in m }.let { if (it < 0) FAMILIES.size else it }
        return family * 2 + free
    }
}
