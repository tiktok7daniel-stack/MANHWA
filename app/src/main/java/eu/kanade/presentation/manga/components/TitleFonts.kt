package eu.kanade.presentation.manga.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import eu.kanade.tachiyomi.R

/** MANHWA: the display fonts a work's title can be set in: 50 Latin ones and the Arabic ones (Google Fonts, OFL/Apache). */
object TitleFonts {
    data class Entry(val key: String, val label: String, val family: FontFamily, val arabic: Boolean = false)

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

    /** MANHWA: Arabic display fonts (Google Fonts, OFL), for Arabic titles: Ruqaa, Kufi, Naskh, Nastaliq and more. */
    val arabic: List<Entry> by lazy {
        listOf(
            Entry("ar_arefruqaa", "رقعة عارف", FontFamily(Font(R.font.ar_arefruqaa)), arabic = true),
            Entry("ar_arefruqaaink", "رقعة عارف ملوّنة", FontFamily(Font(R.font.ar_arefruqaaink)), arabic = true),
            Entry("ar_reemkufi", "ريم كوفي", FontFamily(Font(R.font.ar_reemkufi)), arabic = true),
            Entry("ar_reemkufifun", "ريم كوفي مرح", FontFamily(Font(R.font.ar_reemkufifun)), arabic = true),
            Entry("ar_reemkufiink", "ريم كوفي ملوّن", FontFamily(Font(R.font.ar_reemkufiink)), arabic = true),
            Entry("ar_rakkas", "رقّاص", FontFamily(Font(R.font.ar_rakkas)), arabic = true),
            Entry("ar_lalezar", "لاله‌زار", FontFamily(Font(R.font.ar_lalezar)), arabic = true),
            Entry("ar_jomhuria", "جمهورية", FontFamily(Font(R.font.ar_jomhuria)), arabic = true),
            Entry("ar_katibeh", "كاتبة", FontFamily(Font(R.font.ar_katibeh)), arabic = true),
            Entry("ar_lemonada", "ليمونادة", FontFamily(Font(R.font.ar_lemonada)), arabic = true),
            Entry("ar_marhey", "مرحي", FontFamily(Font(R.font.ar_marhey)), arabic = true),
            Entry("ar_kufam", "كوفام", FontFamily(Font(R.font.ar_kufam)), arabic = true),
            Entry("ar_blaka", "بلاكا", FontFamily(Font(R.font.ar_blaka)), arabic = true),
            Entry("ar_blakahollow", "بلاكا مفرّغ", FontFamily(Font(R.font.ar_blakahollow)), arabic = true),
            Entry("ar_blakaink", "بلاكا ملوّن", FontFamily(Font(R.font.ar_blakaink)), arabic = true),
            Entry("ar_gulzar", "كلزار", FontFamily(Font(R.font.ar_gulzar)), arabic = true),
            Entry("ar_qahiri", "قاهري", FontFamily(Font(R.font.ar_qahiri)), arabic = true),
            Entry("ar_elmessiri", "المسيري", FontFamily(Font(R.font.ar_elmessiri)), arabic = true),
            Entry("ar_mirza", "ميرزا", FontFamily(Font(R.font.ar_mirza)), arabic = true),
            Entry("ar_amiri", "أميري", FontFamily(Font(R.font.ar_amiri)), arabic = true),
            Entry("ar_amiriquran", "أميري قرآني", FontFamily(Font(R.font.ar_amiriquran)), arabic = true),
            Entry("ar_cairo", "القاهرة", FontFamily(Font(R.font.ar_cairo)), arabic = true),
            Entry("ar_tajawal", "تجوّل", FontFamily(Font(R.font.ar_tajawal)), arabic = true),
            Entry("ar_changa", "شانجا", FontFamily(Font(R.font.ar_changa)), arabic = true),
            Entry("ar_harmattan", "هرمتان", FontFamily(Font(R.font.ar_harmattan)), arabic = true),
            Entry("ar_lateef", "لطيف", FontFamily(Font(R.font.ar_lateef)), arabic = true),
            Entry("ar_scheherazadenew", "شهرزاد", FontFamily(Font(R.font.ar_scheherazadenew)), arabic = true),
            Entry("ar_markazitext", "مركزي", FontFamily(Font(R.font.ar_markazitext)), arabic = true),
            Entry("ar_vibes", "فايبز", FontFamily(Font(R.font.ar_vibes)), arabic = true),
            Entry("ar_alkalami", "القلمي", FontFamily(Font(R.font.ar_alkalami)), arabic = true),
            Entry("ar_badeendisplay", "بديع", FontFamily(Font(R.font.ar_badeendisplay)), arabic = true),
            Entry("ar_notokufiarabic", "نوتو كوفي", FontFamily(Font(R.font.ar_notokufiarabic)), arabic = true),
            Entry("ar_notonaskharabic", "نوتو نسخ", FontFamily(Font(R.font.ar_notonaskharabic)), arabic = true),
            Entry("ar_readexpro", "ريدكس", FontFamily(Font(R.font.ar_readexpro)), arabic = true),
            Entry("ar_almarai", "المراعي", FontFamily(Font(R.font.ar_almarai)), arabic = true),
            Entry("ar_mada", "مدى", FontFamily(Font(R.font.ar_mada)), arabic = true),
            Entry("ar_baloobhaijaan2", "بالو بهيجان", FontFamily(Font(R.font.ar_baloobhaijaan2)), arabic = true),
            Entry("ar_handjet", "هاندجت", FontFamily(Font(R.font.ar_handjet)), arabic = true),
            Entry("ar_zain", "زين", FontFamily(Font(R.font.ar_zain)), arabic = true),
            Entry("ar_ruwudu", "رُوُدو", FontFamily(Font(R.font.ar_ruwudu)), arabic = true),
            Entry("ar_playpensansarabic", "بلاي بن", FontFamily(Font(R.font.ar_playpensansarabic)), arabic = true),
            Entry("ar_notonastaliqurdu", "نستعليق", FontFamily(Font(R.font.ar_notonastaliqurdu)), arabic = true),
        )
    }

    fun of(key: String?): Entry? = all.firstOrNull { it.key == key } ?: arabic.firstOrNull { it.key == key }

    /**
     * The font a work gets until one is picked: the same one each time, different from work to work. An Arabic title
     * gets an Arabic font, since the others have no Arabic letters.
     */
    fun forWork(mangaId: Long, title: String = ""): Entry {
        val list = if (isArabic(title)) arabic else all
        return list[((mangaId % list.size + list.size) % list.size).toInt()]
    }

    fun isArabic(text: String): Boolean = text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFEFF' }
}
