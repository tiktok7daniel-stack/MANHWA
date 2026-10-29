package eu.kanade.manhwa

import eu.kanade.tachiyomi.novel.bridge.ChapterMatcher
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterMatcherTest {

    // Made-up chapters of a made-up novel: each has its own names, places and techniques
    private val novel = listOf(
        "Rowan woke in the frozen village of Elderwick. The blacksmith Halvard handed him a cracked sword and spoke of the northern pass.",
        "On the northern pass Rowan met the wandering monk Taesul, who taught him the Crimson Breath technique beside a waterfall.",
        "Taesul warned that the Iron Lotus sect hunted anyone carrying the Crimson Breath. They camped in the ruins of Graywater.",
        "The Iron Lotus elder Morvaine ambushed them at Graywater. Rowan used the Crimson Breath and his cracked sword shattered.",
        "Fleeing south, Rowan reached the merchant city of Valcourt where the guildmaster Ysolde offered a contract for a dragon egg.",
        "Inside the Sunken Archive beneath Valcourt, Rowan and Ysolde found the dragon egg guarded by clockwork sentinels.",
        "The egg hatched into a silver wyrmling Rowan named Ember. Ysolde demanded payment and the sentinels awoke again.",
    )

    @Test
    fun `the chapter that shares the rare names and places wins`() {
        val manhwa = "Morvaine! The Iron Lotus found us at Graywater. Rowan, use the Crimson Breath! My sword... it shattered!"
        val m = ChapterMatcher.best(manhwa, novel).shouldNotBeNull()
        m.index shouldBe 3
        m.confident shouldBe true
    }

    @Test
    fun `a later scene is found as well`() {
        val manhwa = "A silver wyrmling hatched from the dragon egg. I'll call you Ember. Ysolde wants her payment and the sentinels are moving!"
        val m = ChapterMatcher.best(manhwa, novel).shouldNotBeNull()
        m.index shouldBe 6
        m.confident shouldBe true
    }

    @Test
    fun `text that tells nothing gives no answer`() {
        ChapterMatcher.best("What? No! Hey, wait for me...", novel).shouldBeNull()
    }

    @Test
    fun `text from another story is not a confident match`() {
        val other = "The starship Kestrel dropped out of hyperspace near Orion station, commander Lyssa ordered the shields raised against pirates"
        val m = ChapterMatcher.best(other, novel)
        (m == null || !m.confident) shouldBe true
    }
}
