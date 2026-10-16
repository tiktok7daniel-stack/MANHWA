package eu.kanade.translation.ocr

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA PaddleOCR in the app: one [PaddleOcrCore] kept loaded (both models) and reused for every page, for the
 * language being read. Never fails a page: when the models are not on the phone yet, or anything goes wrong,
 * it answers null and ML Kit's reading is used as it is.
 */
object PaddleOcrEngine {

    enum class Mode { DUAL, PADDLE, MLKIT }

    @Volatile private var core: PaddleOcrCore? = null
    @Volatile private var corePack: OcrModels.Pack? = null
    @Volatile private var lastUse = 0L

    /** The engine the reader chose. */
    fun mode(context: Context): Mode =
        runCatching { Mode.valueOf(context.appGraph.translationPreferences.translationOcrEngine().get()) }.getOrDefault(Mode.DUAL)

    fun packFor(language: TextRecognizerLanguage): OcrModels.Pack =
        if (language == TextRecognizerLanguage.KOREAN) OcrModels.Pack.KOREAN else OcrModels.Pack.MAIN

    /**
     * A reader for pages of [language] with the chosen engine, or null when PaddleOCR is not used (ML Kit only,
     * or its models are not on the phone yet: then they are fetched on Wi-Fi for next time).
     */
    fun readerFor(context: Context, language: TextRecognizerLanguage): ((Bitmap) -> List<PaddleOcrCore.Line>?)? {
        if (mode(context) == Mode.MLKIT) return null
        val pack = packFor(language)
        if (!OcrModels.ready(context, pack)) {
            OcrModels.autoDownload(context, pack)
            return null
        }
        val app = context.applicationContext
        return { bitmap -> read(app, bitmap, pack) }
    }

    /** Every line of text on [bitmap]; null when PaddleOCR could not read it (ML Kit carries on alone). */
    @Synchronized
    fun read(context: Context, bitmap: Bitmap, pack: OcrModels.Pack): List<PaddleOcrCore.Line>? {
        val engine = core(context, pack) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            lastUse = System.currentTimeMillis()
            engine.read(pixels, w, h)
        } catch (e: OutOfMemoryError) {
            logcat(LogPriority.WARN, e) { "PaddleOCR ran out of memory; ML Kit reads this page" }
            release()
            null
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "PaddleOCR could not read this page; ML Kit reads it" }
            null
        }
    }

    private fun core(context: Context, pack: OcrModels.Pack): PaddleOcrCore? {
        core?.takeIf { corePack == pack }?.let { return it }
        release()
        return try {
            val det = OcrModels.file(context, OcrModels.Pack.MAIN, OcrModels.DET)
            val rec = OcrModels.file(context, pack, OcrModels.REC)
            val dict = PaddleOcrCore.readDict(OcrModels.file(context, pack, OcrModels.DICT))
            PaddleOcrCore(det, rec, dict).also {
                core = it
                corePack = pack
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "PaddleOCR could not start; ML Kit reads alone" }
            null
        }
    }

    /** Frees the models' memory (models replaced, low memory, or not used for a while). */
    @Synchronized
    fun release() {
        runCatching { core?.close() }
        core = null
        corePack = null
    }

    /** Frees the models when they were not used for a while (called by the background worker). */
    fun trim() {
        if (core != null && System.currentTimeMillis() - lastUse > IDLE_MS) release()
    }

    private const val IDLE_MS = 5 * 60 * 1000L
}
