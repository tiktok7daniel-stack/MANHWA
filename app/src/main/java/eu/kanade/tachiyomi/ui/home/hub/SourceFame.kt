package eu.kanade.tachiyomi.ui.home.hub

import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * MANHWA source fame index: how well known and active a source is in the manhwa world, from a built-in
 * knowledge base of the big sources of each language, completed by the team's AI for the ones it doesn't know.
 * Scores go from 0 to 100; null means nobody knows the source yet.
 */
object SourceFame {

    enum class Tier { S, OFFICIAL, TOP, POPULAR }

    /** Normalized name fragment to score. The longest matching fragment wins. */
    private val known: Map<String, Int> = mapOf(
        // Arabic
        "teamx" to 98, "team x" to 98, "azora" to 95, "gmanga" to 93, "mangaswat" to 91, "swatmanga" to 91,
        "olimanga" to 86, "areamanga" to 85, "areascans" to 85, "mangalek" to 84, "lekmanga" to 84, "3asq" to 82,
        "galaxymanga" to 81, "dilar" to 80, "thunderscans" to 80, "rocksmanga" to 80, "mangastarz" to 78,
        "mangaspark" to 78, "arenascans" to 75, "crowscans" to 72, "procomic" to 72, "hijala" to 70, "yonabar" to 70,
        "ysk" to 68, "potatomanga" to 68, "manhatok" to 66,
        // English
        "asura" to 100, "reaperscans" to 95, "reaper scans" to 95, "flamecomics" to 94, "flamescans" to 94,
        "webtoons" to 97, "mangadex" to 96, "mangaplus" to 96, "comick" to 92, "mangafire" to 89, "bato" to 88,
        "weebcentral" to 87, "mangasee" to 86, "hivescans" to 85, "hivetoon" to 85, "tapas" to 85, "manganato" to 84,
        "natomanga" to 84, "mangakakalot" to 83, "vortexscans" to 82, "mangageko" to 80, "mgeko" to 80, "mangapark" to 80,
        "zeroscans" to 78, "drakescans" to 78, "mangabuddy" to 77, "luminousscans" to 75, "nightscans" to 75,
        "rizzcomic" to 75, "rizzfables" to 75, "manhuaplus" to 74, "arvenscans" to 72, "kunmanga" to 70,
        // Korean
        "naver" to 99, "kakao" to 97, "newtoki" to 92, "manatoki" to 90, "lezhin" to 85, "toomics" to 80,
        "bomtoon" to 78, "toptoon" to 76,
        // Spanish
        "olympus" to 95, "tumanga" to 94, "lectortmo" to 94, "zonatmo" to 92, "manhwaweb" to 86, "inmanga" to 80,
        "dragontranslation" to 76, "barmanga" to 75, "leermanga" to 74,
        // French
        "phenixscans" to 92, "japscan" to 92, "sushiscan" to 90, "scanvf" to 84, "scanmanga" to 84, "lelscan" to 80,
        "mangasorigines" to 80, "raijinscans" to 78,
        // Indonesian
        "shinigami" to 95, "komikcast" to 92, "kiryuu" to 88, "komiku" to 88, "westmanga" to 86, "komikindo" to 85,
        "bacakomik" to 82, "mgkomik" to 80,
        // Portuguese
        "slimeread" to 85, "mangalivre" to 85, "sakuramangas" to 82, "lermanga" to 80, "taiyo" to 80,
        // Russian
        "remanga" to 95, "mangalib" to 92, "readmanga" to 88, "mintmanga" to 82, "senkuro" to 80,
        // Turkish
        "webtoontr" to 85, "mangatr" to 82, "golgebahcesi" to 78,
        // Vietnamese and Chinese
        "nettruyen" to 88, "truyenqq" to 85, "cuutruyen" to 85, "blogtruyen" to 82,
        "bilibili" to 92, "manhuagui" to 88, "copymanga" to 88, "dmzj" to 85,
    )

    private val official = setOf("webtoons", "mangaplus", "naver", "kakao", "tapas", "lezhin", "toomics", "bilibili", "bomtoon", "toptoon")

    /** The order the language chips start with: the big manhwa languages first. */
    val languagePriority = listOf("ar", "en", "ko", "es", "fr", "id", "pt-BR", "pt", "ru", "tr", "vi", "zh", "ja", "all")

    /** AI scores for sources the knowledge base doesn't know, by normalized name. */
    @Volatile
    private var ai: Map<String, Int> = emptyMap()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadAi(raw: String) {
        ai = runCatching {
            json.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.toInt()?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun aiRaw(): String = JsonObject(ai.mapValues { JsonPrimitive(it.value) }).toString()

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() || it == ' ' }

    /** The source's fame, or null when neither the knowledge base nor the AI knows it. */
    fun score(name: String): Int? {
        val n = normalize(name)
        val compact = n.replace(" ", "")
        val hit = known.entries.filter { (k, _) -> compact.contains(k.replace(" ", "")) }.maxByOrNull { it.key.length }
        return hit?.value ?: ai[n]
    }

    fun tier(name: String): Tier? {
        val s = score(name) ?: return null
        val compact = normalize(name).replace(" ", "")
        return when {
            official.any { compact.contains(it) } -> Tier.OFFICIAL
            s >= 93 -> Tier.S
            s >= 85 -> Tier.TOP
            s >= 75 -> Tier.POPULAR
            else -> null
        }
    }

    /** Names the index has no score for yet. */
    fun unknown(names: Collection<String>): List<String> = names.filter { score(it) == null }.distinct()

    /**
     * Asks the team's AI to score [names] it doesn't know yet. Returns true when the index learnt something.
     * Never throws: without a key or on a failure the knowledge base simply stays as it is.
     */
    suspend fun learn(names: Collection<String>): Boolean {
        val todo = unknown(names).take(MAX_AI_NAMES)
        if (todo.isEmpty() || !appAiConfigured()) return false
        return runCatching {
            val reply = appAsk(AiRouter.Lane.BACKGROUND) { model ->
                model.askJson(
                    "You know the manga, manhwa and webtoon reading scene worldwide. Score each source name from 0 to 100 " +
                        "for how popular, active and trusted it is for manhwa readers (100 = the biggest of its language). " +
                        "Unknown or dead sources get 20 or less. Reply with ONLY a JSON object {\"name\": score}.",
                    todo.joinToString("\n"),
                )
            }
            val learnt = reply.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.toInt()?.coerceIn(0, 100)?.let { normalize(k) to it } }
            if (learnt.isEmpty()) return@runCatching false
            ai = ai + learnt
            true
        }.getOrDefault(false)
    }

    private const val MAX_AI_NAMES = 80
}
