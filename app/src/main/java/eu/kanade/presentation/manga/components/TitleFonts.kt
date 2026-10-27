package eu.kanade.presentation.manga.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import eu.kanade.tachiyomi.R

/** MANHWA: the 50 display fonts a work's title can be set in (Google Fonts, OFL/Apache, Latin letters only). */
object TitleFonts {
    data class Entry(val key: String, val label: String, val family: FontFamily)

    val all: List<Entry> by lazy {
        listOf(
            Entry("cinzel", "Cinzel", FontFamily(Font(R.font.fx_cinzel))),
            Entry("cinzeldecorative", "Cinzel Decorative", FontFamily(Font(R.font.fx_cinzeldecorative))),
            Entry("unifrakturmaguntia", "UnifrakturMaguntia", FontFamily(Font(R.font.fx_unifrakturmaguntia))),
            Entry("pirataone", "Pirata One", FontFamily(Font(R.font.fx_pirataone))),
            Entry("metalmania", "Metal Mania", FontFamily(Font(R.font.fx_metalmania))),
            Entry("newrocker", "New Rocker", FontFamily(Font(R.font.fx_newrocker))),
            Entry("creepster", "Creepster", FontFamily(Font(R.font.fx_creepster))),
            Entry("nosifer", "Nosifer", FontFamily(Font(R.font.fx_nosifer))),
            Entry("butcherman", "Butcherman", FontFamily(Font(R.font.fx_butcherman))),
            Entry("eater", "Eater", FontFamily(Font(R.font.fx_eater))),
            Entry("grenzegotisch", "Grenze Gotisch", FontFamily(Font(R.font.fx_grenzegotisch))),
            Entry("uncialantiqua", "Uncial Antiqua", FontFamily(Font(R.font.fx_uncialantiqua))),
            Entry("almendradisplay", "Almendra Display", FontFamily(Font(R.font.fx_almendradisplay))),
            Entry("medievalsharp", "MedievalSharp", FontFamily(Font(R.font.fx_medievalsharp))),
            Entry("imfellenglishsc", "IM Fell English SC", FontFamily(Font(R.font.fx_imfellenglishsc))),
            Entry("cormorantsc", "Cormorant SC", FontFamily(Font(R.font.fx_cormorantsc))),
            Entry("playfairdisplaysc", "Playfair Display SC", FontFamily(Font(R.font.fx_playfairdisplaysc))),
            Entry("bebasneue", "Bebas Neue", FontFamily(Font(R.font.fx_bebasneue))),
            Entry("anton", "Anton", FontFamily(Font(R.font.fx_anton))),
            Entry("righteous", "Righteous", FontFamily(Font(R.font.fx_righteous))),
            Entry("monoton", "Monoton", FontFamily(Font(R.font.fx_monoton))),
            Entry("audiowide", "Audiowide", FontFamily(Font(R.font.fx_audiowide))),
            Entry("orbitron", "Orbitron", FontFamily(Font(R.font.fx_orbitron))),
            Entry("bungee", "Bungee", FontFamily(Font(R.font.fx_bungee))),
            Entry("bungeeshade", "Bungee Shade", FontFamily(Font(R.font.fx_bungeeshade))),
            Entry("bungeeinline", "Bungee Inline", FontFamily(Font(R.font.fx_bungeeinline))),
            Entry("fasterone", "Faster One", FontFamily(Font(R.font.fx_fasterone))),
            Entry("russoone", "Russo One", FontFamily(Font(R.font.fx_russoone))),
            Entry("blackopsone", "Black Ops One", FontFamily(Font(R.font.fx_blackopsone))),
            Entry("pressstart2p", "Press Start 2P", FontFamily(Font(R.font.fx_pressstart2p))),
            Entry("specialelite", "Special Elite", FontFamily(Font(R.font.fx_specialelite))),
            Entry("rye", "Rye", FontFamily(Font(R.font.fx_rye))),
            Entry("ewert", "Ewert", FontFamily(Font(R.font.fx_ewert))),
            Entry("vastshadow", "Vast Shadow", FontFamily(Font(R.font.fx_vastshadow))),
            Entry("fascinateinline", "Fascinate Inline", FontFamily(Font(R.font.fx_fascinateinline))),
            Entry("sancreek", "Sancreek", FontFamily(Font(R.font.fx_sancreek))),
            Entry("diplomatasc", "Diplomata SC", FontFamily(Font(R.font.fx_diplomatasc))),
            Entry("ultra", "Ultra", FontFamily(Font(R.font.fx_ultra))),
            Entry("abrilfatface", "Abril Fatface", FontFamily(Font(R.font.fx_abrilfatface))),
            Entry("bowlbyonesc", "Bowlby One SC", FontFamily(Font(R.font.fx_bowlbyonesc))),
            Entry("teko", "Teko", FontFamily(Font(R.font.fx_teko))),
            Entry("rajdhani", "Rajdhani", FontFamily(Font(R.font.fx_rajdhani))),
            Entry("oxanium", "Oxanium", FontFamily(Font(R.font.fx_oxanium))),
            Entry("syncopate", "Syncopate", FontFamily(Font(R.font.fx_syncopate))),
            Entry("michroma", "Michroma", FontFamily(Font(R.font.fx_michroma))),
            Entry("zendots", "Zen Dots", FontFamily(Font(R.font.fx_zendots))),
            Entry("kaushanscript", "Kaushan Script", FontFamily(Font(R.font.fx_kaushanscript))),
            Entry("greatvibes", "Great Vibes", FontFamily(Font(R.font.fx_greatvibes))),
            Entry("pinyonscript", "Pinyon Script", FontFamily(Font(R.font.fx_pinyonscript))),
            Entry("tangerine", "Tangerine", FontFamily(Font(R.font.fx_tangerine))),
        )
    }

    fun of(key: String?): Entry? = all.firstOrNull { it.key == key }

    /** The font a work gets until one is picked: the same one each time, different from work to work. */
    fun forWork(mangaId: Long): Entry = all[((mangaId % all.size + all.size) % all.size).toInt()]
}
