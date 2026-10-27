package eu.kanade.translation.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.presentation.components.CoverGlow
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * MANHWA visual recap: the story map as a card to share, in the cover's colours. The cover on top, the title, how
 * far the reader is, and the key events down a glowing line (never past the chapter read).
 */
object RecapCard {

    private const val W = 1080
    private const val H = 1920

    suspend fun share(context: Context, manga: Manga, map: StoryMap.Map, heading: String, upToLine: String) {
        val file = withContext(Dispatchers.Default) { render(context, manga, map, heading, upToLine) } ?: return
        context.startActivity(file.getUriCompat(context).toShareIntent(context, "image/png"))
    }

    private suspend fun render(context: Context, manga: Manga, map: StoryMap.Map, heading: String, upToLine: String): File? {
        val palette = CoverGlow.paletteOf(context, manga)
        val accent = palette?.primary?.toArgb() ?: 0xFFFF3B4E.toInt()
        val deep = palette?.deep?.toArgb() ?: 0xFF0B0B10.toInt()
        val cover = runCatching {
            val request = ImageRequest.Builder(context).data(manga).allowHardware(false).build()
            (context.imageLoader.execute(request).image?.asDrawable(context.resources) as? BitmapDrawable)?.bitmap
        }.getOrNull()

        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background: the cover, dark, under the cover's deep shade
        paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), deep, 0xFF050507.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        cover?.let {
            paint.alpha = 60
            val scale = maxOf(W.toFloat() / it.width, 900f / it.height)
            val dw = it.width * scale
            val dh = it.height * scale
            canvas.drawBitmap(it, null, RectF((W - dw) / 2, 0f, (W + dw) / 2, dh), paint)
            paint.alpha = 255
        }
        paint.shader = LinearGradient(0f, 300f, 0f, 1000f, 0x00000000, deep, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 300f, W.toFloat(), 1000f, paint)
        paint.shader = null

        // The cover, with a glow of the accent colour
        val coverRect = RectF(W / 2f - 210f, 120f, W / 2f + 210f, 120f + 630f)
        paint.color = accent
        paint.alpha = 90
        paint.setShadowLayer(60f, 0f, 0f, accent)
        canvas.drawRoundRect(coverRect, 28f, 28f, paint)
        paint.clearShadowLayer()
        paint.alpha = 255
        cover?.let {
            val save = canvas.save()
            val path = android.graphics.Path().apply { addRoundRect(coverRect, 28f, 28f, android.graphics.Path.Direction.CW) }
            canvas.clipPath(path)
            canvas.drawBitmap(it, null, coverRect, paint)
            canvas.restoreToCount(save)
        }

        var y = coverRect.bottom + 60f
        y = text(canvas, manga.title, y, 64f, 0xFFFFFFFF.toInt(), bold = true, maxLines = 2)
        y = text(canvas, heading.uppercase(), y + 10f, 30f, accent, bold = true)
        y = text(canvas, upToLine, y + 6f, 28f, 0xAAFFFFFF.toInt())

        // The events down a line
        val events = map.events.filter { it.title.isNotBlank() || it.text.isNotBlank() }.takeLast(6)
        val lineX = 110f
        var top = y + 50f
        paint.color = 0x33FFFFFF
        paint.strokeWidth = 4f
        if (events.isNotEmpty()) canvas.drawLine(lineX, top + 20f, lineX, minOf(H - 160f, top + events.size * 150f), paint)
        events.forEach { e ->
            if (top > H - 260f) return@forEach
            paint.color = accent
            canvas.drawCircle(lineX, top + 24f, 14f, paint)
            val chapter = e.chapter.ifBlank { "#${e.number}" }
            var ty = text(canvas, chapter, top, 26f, accent, left = lineX + 40f, bold = true, center = false)
            ty = text(canvas, e.title.ifBlank { e.text }, ty, 34f, 0xFFFFFFFF.toInt(), left = lineX + 40f, bold = true, center = false, maxLines = 2)
            if (e.title.isNotBlank() && e.text.isNotBlank()) {
                ty = text(canvas, e.text, ty + 2f, 26f, 0xBBFFFFFF.toInt(), left = lineX + 40f, center = false, maxLines = 2)
            }
            top = ty + 34f
        }

        // The signature
        text(canvas, "MANHWA", H - 110f, 34f, accent, bold = true)

        val dir = context.cacheImageDir.apply { mkdirs() }
        val file = File(dir, "recap_${manga.id}.png")
        return runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }.getOrNull()
    }

    /** Draws [value] wrapped (right to left when it is Arabic) and returns where the next line starts. */
    private fun text(
        canvas: Canvas,
        value: String,
        y: Float,
        size: Float,
        color: Int,
        left: Float = 80f,
        bold: Boolean = false,
        center: Boolean = true,
        maxLines: Int = 3,
    ): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            isFakeBoldText = bold
        }
        val width = (W - left - 80f).toInt()
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(left, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }
}
