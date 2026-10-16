package eu.kanade.translation.characters

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.glossary.WorkIdentity
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.ArabicTransliterator
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * MANHWA characters: the cast of a work (the hero, the main and supporting characters, with their pictures,
 * gender and who they are), read from AniList's public API (no key and no account needed; one request per work,
 * kept for a week, at most one request a second, the way its terms ask).
 *
 * Every character also goes into the work's glossary as a sure name with its gender, so the translator writes
 * the names right from the first chapter and Arabic verbs agree with who speaks. What the reader corrected in
 * the glossary always wins. Without internet, or for a work AniList does not know, the cast comes from the
 * characters the translation itself found in the glossary.
 */
@Inject
@SingleIn(AppScope::class)
class CharacterStore(
    private val context: Context,
    private val network: NetworkHelper,
    private val glossary: GlossaryStore,
) {

    @Serializable
    data class Character(
        val id: Int,
        val name: String,
        val native: String = "",
        val alternative: List<String> = emptyList(),
        val image: String = "",
        /** MAIN, SUPPORTING or BACKGROUND (AniList's roles); the first MAIN one is the hero. */
        val role: String = "",
        val gender: String = "",
        val about: String = "",
        val arabicName: String = "",
        val arabicAbout: String = "",
    )

    @Serializable
    data class Candidate(val id: Int, val title: String, val country: String = "")

    @Serializable
    data class Cast(
        val workKey: String,
        val anilistId: Int = 0,
        val title: String = "",
        val characters: List<Character> = emptyList(),
        val fetchedAt: Long = 0,
        /** The title did not match exactly: the reader may pick the right work from [candidates]. */
        val uncertain: Boolean = false,
        val candidates: List<Candidate> = emptyList(),
        /** From the glossary only (AniList gave nothing). */
        val local: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Mutex()
    private var lastCall = 0L

    private fun dir() = File(context.filesDir, "characters").apply { mkdirs() }
    private fun file(key: String) = File(dir(), "$key.json")

    fun cached(title: String): Cast? = runCatching {
        file(WorkIdentity.key(title).ifBlank { return null }).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Cast>(it) }
    }.getOrNull()

    private fun save(cast: Cast) {
        runCatching { file(cast.workKey).writeText(json.encodeToString(cast)) }
    }

    /** The cast of [title] (series [mangaId]): the week's copy, or AniList now, or the glossary. */
    suspend fun load(mangaId: Long, title: String, force: Boolean = false, anilistId: Int? = null): Cast = withContext(Dispatchers.IO) {
        val key = WorkIdentity.key(title).ifBlank { "m$mangaId" }
        val old = cached(title)
        if (!force && anilistId == null && old != null && !old.local && System.currentTimeMillis() - old.fetchedAt < WEEK_MS) return@withContext old
        val fetched = runCatching { fetch(key, title, anilistId) }
            .onFailure { logcat(LogPriority.WARN, it) { "AniList characters not read for $title" } }
            .getOrNull()
        val cast = when {
            fetched != null && fetched.characters.isNotEmpty() -> fetched.withArabicNames(mangaId)
            old != null -> old
            else -> fromGlossary(mangaId, key)
        }
        save(cast)
        if (!cast.local) teachGlossary(mangaId, cast)
        cast
    }

    /** Characters the translation found by itself, when AniList has nothing. */
    private fun fromGlossary(mangaId: Long, key: String): Cast {
        val people = glossary.get(mangaId).filter { it.kind.contains("character", ignoreCase = true) || it.gender.isNotBlank() }
        return Cast(
            workKey = key,
            characters = people.mapIndexed { i, e ->
                Character(id = -(i + 1), name = e.source, gender = e.gender, about = e.note, arabicName = e.translation, role = if (i == 0) "MAIN" else "SUPPORTING")
            },
            fetchedAt = System.currentTimeMillis(),
            local = true,
        )
    }

    private fun Cast.withArabicNames(mangaId: Long): Cast {
        val known = glossary.asMap(mangaId).mapKeys { it.key.lowercase() }
        return copy(
            characters = characters.map { c ->
                val fromGlossary = (listOf(c.name) + c.alternative + c.name.split(' ')).firstNotNullOfOrNull { known[it.lowercase()] }
                c.copy(arabicName = c.arabicName.ifBlank { fromGlossary ?: ArabicTransliterator.transliterate(c.name.replace(' ', '-')).replace('-', ' ') })
            },
        )
    }

    /** Names and genders into the glossary (never over what is there: the reader's corrections win). */
    private fun teachGlossary(mangaId: Long, cast: Cast) {
        val terms = LinkedHashMap<String, String>()
        val details = LinkedHashMap<String, Pair<String, String>>()
        cast.characters.filter { it.role != "BACKGROUND" }.forEach { c ->
            val gender = when (c.gender.lowercase()) {
                "male" -> "male"
                "female" -> "female"
                else -> ""
            }
            (listOf(c.name) + c.alternative.filter { alt -> alt.all { it.code < 0x2000 } }).distinct().forEach { n ->
                if (n.length >= 2 && c.arabicName.isNotBlank()) {
                    terms[n] = c.arabicName
                    details[n] = "character" to gender
                }
            }
        }
        glossary.merge(mangaId, terms, details)
    }

    /** "Who is this?" in Arabic, asked once per character and kept. */
    suspend fun arabicAbout(title: String, character: Character): String = withContext(Dispatchers.IO) {
        if (character.arabicAbout.isNotBlank() || character.about.isBlank() || !appAiConfigured()) return@withContext character.arabicAbout
        val text = runCatching {
            appAsk(AiRouter.Lane.BACKGROUND) { ai ->
                ai.askJson(
                    system = "You write short character profiles for Arabic manhwa readers. Reply with JSON only.",
                    prompt = "Work: $title\nCharacter: ${character.name}\nAbout (English): ${character.about.take(1500)}\n" +
                        "Write 2 to 4 natural Arabic sentences on who this character is, without spoilers past the first arcs. " +
                        "Reply as {\"t\":\"...\"}",
                )
            }["t"].let { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        }.getOrDefault("")
        if (text.isNotBlank()) {
            cached(title)?.let { cast -> save(cast.copy(characters = cast.characters.map { if (it.id == character.id) it.copy(arabicAbout = text) else it })) }
        }
        text
    }

    private suspend fun fetch(key: String, title: String, anilistId: Int?): Cast? = gate.withLock {
        // One request a second at most
        val wait = 1_000 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        val variables = buildJsonObject { if (anilistId != null) put("id", anilistId) else put("search", title.take(100)) }
        val body = buildJsonObject {
            put("query", if (anilistId != null) QUERY_ID else QUERY_SEARCH)
            put("variables", variables)
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = network.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("AniList HTTP ${r.code}")
            r.body.string()
        }
        val data = json.parseToJsonElement(text).jsonObject["data"] as? JsonObject ?: return@withLock null
        val list = if (anilistId != null) {
            listOfNotNull(data["Media"] as? JsonObject)
        } else {
            ((data["Page"] as? JsonObject)?.get("media") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
        if (list.isEmpty()) return@withLock null
        val candidates = list.map { m -> Candidate(m.int("id") ?: 0, titlesOf(m).firstOrNull().orEmpty(), m.str("countryOfOrigin").orEmpty()) }
        val exact = list.firstOrNull { m -> titlesOf(m).any { WorkIdentity.same(it, title) } }
        val media = exact ?: list.first()
        Cast(
            workKey = key,
            anilistId = media.int("id") ?: 0,
            title = titlesOf(media).firstOrNull().orEmpty(),
            characters = parseCharacters(media),
            fetchedAt = System.currentTimeMillis(),
            uncertain = exact == null && anilistId == null,
            candidates = candidates,
        )
    }

    private fun titlesOf(m: JsonObject): List<String> {
        val t = m["title"] as? JsonObject
        return listOfNotNull(t?.str("english"), t?.str("romaji"), t?.str("native")) +
            (m["synonyms"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun parseCharacters(media: JsonObject): List<Character> {
        val edges = ((media["characters"] as? JsonObject)?.get("edges") as? JsonArray).orEmpty()
        return edges.mapNotNull { e ->
            val edge = e as? JsonObject ?: return@mapNotNull null
            val node = edge["node"] as? JsonObject ?: return@mapNotNull null
            val name = node["name"] as? JsonObject
            Character(
                id = node.int("id") ?: return@mapNotNull null,
                name = name?.str("full") ?: return@mapNotNull null,
                native = name?.str("native").orEmpty(),
                alternative = (name?.get("alternative") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() },
                image = (node["image"] as? JsonObject)?.let { it.str("large") ?: it.str("medium") }.orEmpty(),
                role = edge.str("role").orEmpty(),
                gender = node.str("gender").orEmpty(),
                about = cleanAbout(node.str("description").orEmpty()),
            )
        }.sortedBy { CastRules.roleOrder(it.role) }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    /** AniList descriptions carry markdown and spoiler marks: plain text, spoilers removed. */
    private fun cleanAbout(text: String): String = text
        .replace(Regex("~!.*?!~", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("__|\\*\\*|\\[|\\]\\([^)]*\\)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(1500)

    private companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        private const val FIELDS = """
            id title { romaji english native } synonyms countryOfOrigin
            characters(perPage: 25, sort: [ROLE, RELEVANCE]) {
              edges { role node { id name { full native alternative } image { large medium } gender description } }
            }
        """
        const val QUERY_SEARCH = "query (\$search: String) { Page(perPage: 5) { media(search: \$search, type: MANGA) { $FIELDS } } }"
        const val QUERY_ID = "query (\$id: Int) { Media(id: \$id, type: MANGA) { $FIELDS } }"
    }
}

/** Pure rules of the cast (tested without a phone). */
object CastRules {
    fun roleOrder(role: String): Int = when (role.uppercase()) {
        "MAIN" -> 0
        "SUPPORTING" -> 1
        else -> 2
    }

    /** The hero: the first main character. */
    fun heroOf(characters: List<CharacterStore.Character>): CharacterStore.Character? = characters.firstOrNull { it.role.equals("MAIN", true) }
}
