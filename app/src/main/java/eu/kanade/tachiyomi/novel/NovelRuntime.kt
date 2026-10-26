package eu.kanade.tachiyomi.novel

import android.content.Context
import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import ireader.core.http.BrowserEngine
import ireader.core.http.CookieSynchronizer
import ireader.core.http.HttpClientsInterface
import ireader.core.http.NetworkConfig
import ireader.core.http.SSLConfiguration
import ireader.core.prefs.Preference
import ireader.core.prefs.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import okhttp3.OkHttpClient

/**
 * MANHWA novels: the network an IReader novel extension is given. Both its clients are the app's own OkHttp client
 * (its cookies, its user agent, and its Cloudflare handling, which is the WebView page the reader solves
 * themselves); nothing here gets around a site's protection.
 */
class NovelHttpClients(okHttp: OkHttpClient) : HttpClientsInterface {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override val default: HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }

    override val cloudflareClient: HttpClient get() = default

    override val browser: BrowserEngine = BrowserEngine()
    override val config: NetworkConfig = NetworkConfig()
    override val sslConfig: SSLConfiguration = SSLConfiguration()
    override val cookieSynchronizer: CookieSynchronizer = CookieSynchronizer()
}

/** An extension's own settings, in a preferences file of its own (as IReader keeps them per package). */
class NovelPreferenceStore(context: Context, pkg: String) : PreferenceStore {

    private val prefs: SharedPreferences = context.getSharedPreferences("novel_ext_$pkg", Context.MODE_PRIVATE)

    override fun getString(key: String, defaultValue: String): Preference<String> =
        Pref(key, defaultValue, { getString(key, defaultValue) ?: defaultValue }, { putString(key, it) })

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        Pref(key, defaultValue, { getLong(key, defaultValue) }, { putLong(key, it) })

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        Pref(key, defaultValue, { getInt(key, defaultValue) }, { putInt(key, it) })

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        Pref(key, defaultValue, { getFloat(key, defaultValue) }, { putFloat(key, it) })

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        Pref(key, defaultValue, { getBoolean(key, defaultValue) }, { putBoolean(key, it) })

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        Pref(key, defaultValue, { getStringSet(key, defaultValue)?.toSet() ?: defaultValue }, { putStringSet(key, it) })

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = Pref(
        key,
        defaultValue,
        { getString(key, null)?.let { runCatching { deserializer(it) }.getOrNull() } ?: defaultValue },
        { putString(key, serializer(it)) },
    )

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule,
    ): Preference<T> {
        val format = Json { this.serializersModule = serializersModule }
        return getObject(key, defaultValue, { format.encodeToString(serializer, it) }, { format.decodeFromString(serializer, it) })
    }

    private inner class Pref<T>(
        private val key: String,
        private val default: T,
        private val read: SharedPreferences.() -> T,
        private val write: SharedPreferences.Editor.(T) -> Unit,
    ) : Preference<T> {
        override fun key(): String = key
        override fun get(): T = prefs.read()
        override fun set(value: T) = prefs.edit().apply { write(value) }.apply()
        override fun isSet(): Boolean = prefs.contains(key)
        override fun delete() = prefs.edit().remove(key).apply()
        override fun defaultValue(): T = default
        override fun changes(): Flow<T> = callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (changed == key) trySend(get())
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}
