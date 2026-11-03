package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.render.ScanlationRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MANHWA floating reader: the chapter in a small window of the app's own, over any other app, instead of Android's
 * picture-in-picture (which lets nobody scroll inside it). It scrolls with a finger, moves by its top bar, is resized
 * from its corner, and never takes the keyboard, so the app under it can still be typed in. Its pages are the
 * reader's own (loaded by the reader, translated when the reader has a translation for them).
 */
object FloatingReader {

    private var window: View? = null
    private var scope: CoroutineScope? = null
    private val cache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    val showing: Boolean get() = window != null

    /** Whether the app may draw over other apps. */
    fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Opens the system page where the reader lets the app draw over other apps. */
    fun askPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Shows [pages] in the floating window, at page [start] (0-based). */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, pages: List<ReaderPage>, start: Int) {
        if (pages.isEmpty() || !allowed(context)) return
        close()
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = app.resources.displayMetrics
        val dp = metrics.density
        val job = SupervisorJob()
        val windowScope = CoroutineScope(job + Dispatchers.Main)
        scope = windowScope
        cache.evictAll()

        val params = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.62f).roundToInt(),
            (metrics.heightPixels * 0.5f).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the keyboard: the app under it is still typed in
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels * 0.34f).roundToInt()
            y = (metrics.heightPixels * 0.3f).roundToInt()
        }

        val root = FrameLayout(app).apply {
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(0xF0121216.toInt())
                setStroke((1 * dp).roundToInt(), 0x55FF4D6D)
            }
            clipToOutline = true
            elevation = 12 * dp
        }
        val column = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The top bar: drag it to move the window; open the full reader or close it
        val bar = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * dp).roundToInt(), 0, (4 * dp).roundToInt(), 0)
            setBackgroundColor(0xFF1C1C22.toInt())
        }
        val title = TextView(app).apply {
            text = "MANHWA  ${start + 1} / ${pages.size}"
            setTextColor(0xFFE8E8EE.toInt())
            textSize = 12f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun icon(res: Int, onClick: () -> Unit) = ImageView(app).apply {
            setImageResource(res)
            setColorFilter(0xFFE8E8EE.toInt())
            val pad = (8 * dp).roundToInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
        bar.addView(icon(R.drawable.ic_float_open) { openFull(app) }, LinearLayout.LayoutParams((40 * dp).roundToInt(), (40 * dp).roundToInt()))
        bar.addView(icon(R.drawable.ic_float_close) { close() }, LinearLayout.LayoutParams((40 * dp).roundToInt(), (40 * dp).roundToInt()))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * dp).roundToInt()))

        // The pages, scrolled with a finger
        val list = RecyclerView(app).apply {
            layoutManager = LinearLayoutManager(app)
            setHasFixedSize(false)
            adapter = PagesAdapter(pages, windowScope) { params.width }
            scrollToPosition(start.coerceIn(0, pages.lastIndex))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val at = (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                    if (at >= 0) title.text = "MANHWA  ${at + 1} / ${pages.size}"
                }
            })
        }
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The corner: drag it to make the window bigger or smaller
        val grip = ImageView(app).apply {
            setImageResource(R.drawable.ic_float_resize)
            setColorFilter(0xCCFF4D6D.toInt())
            rotation = 90f
            val pad = (6 * dp).roundToInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(0xAA1C1C22.toInt())
            }
        }
        root.addView(
            grip,
            FrameLayout.LayoutParams((34 * dp).roundToInt(), (34 * dp).roundToInt(), Gravity.BOTTOM or Gravity.END),
        )

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var startW = 0
        var startH = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).roundToInt()
                    params.y = (startY + (e.rawY - downY)).roundToInt()
                    runCatching { wm.updateViewLayout(root, params) }
                }
            }
            true
        }
        val minW = (160 * dp).roundToInt()
        val minH = (200 * dp).roundToInt()
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = params.width
                    startH = params.height
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (e.rawX - downX)).roundToInt().coerceIn(minW, metrics.widthPixels)
                    params.height = (startH + (e.rawY - downY)).roundToInt().coerceIn(minH, metrics.heightPixels)
                    runCatching { wm.updateViewLayout(root, params) }
                }
                MotionEvent.ACTION_UP -> list.adapter?.notifyDataSetChanged()
            }
            true
        }

        runCatching { wm.addView(root, params) }
            .onSuccess { window = root }
            .onFailure {
                logcat(LogPriority.WARN, it) { "Floating reader could not be shown" }
                windowScope.cancel()
                scope = null
            }
    }

    fun close() {
        val view = window ?: return
        window = null
        scope?.cancel()
        scope = null
        val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        cache.evictAll()
    }

    /** Back to the full reader (it stayed open behind the window). */
    private fun openFull(context: Context) {
        close()
        val intent = Intent(context, ReaderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
    }

    private class PagesAdapter(
        private val pages: List<ReaderPage>,
        private val scope: CoroutineScope,
        private val width: () -> Int,
    ) : RecyclerView.Adapter<PagesAdapter.Holder>() {

        class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
            var job: Job? = null
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(parent.context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minimumHeight = (parent.context.resources.displayMetrics.density * 240).roundToInt()
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.job?.cancel()
            holder.image.setImageBitmap(null)
            cache.get(position)?.let {
                holder.image.setImageBitmap(it)
                return
            }
            val page = pages[position]
            holder.job = scope.launch {
                val bitmap = try {
                    load(holder.image.context, page, width())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "Floating reader page ${position + 1} not shown" }
                    null
                } ?: return@launch
                cache.put(position, bitmap)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bitmap)
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.image.setImageBitmap(null)
        }
    }

    /** [page] as a bitmap about [targetWidth] wide: loaded by the reader's own loader, translated when it can be. */
    private suspend fun load(context: Context, page: ReaderPage, targetWidth: Int): Bitmap? {
        if (page.stream == null) {
            val loader = runCatching { page.chapter.pageLoader }.getOrNull() ?: return null
            // Loading may not return (the loader keeps watching the page): its status tells when the image is in
            scope?.launch(Dispatchers.IO) { runCatching { loader.loadPage(page) } }
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            }
        }
        val stream = page.stream ?: return null
        return withContext(Dispatchers.IO) {
            val buffer = stream().use { Buffer().readFrom(it) }
            val translated = page.translation?.takeIf { t -> t.blocks.any { it.translation.isNotBlank() } }
                ?.let { ScanlationRenderer.bake(context, buffer.copy(), it) }
            val bytes = (translated ?: buffer).readByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val wanted = max(targetWidth, 320)
            while (bounds.outWidth / (sample * 2) >= wanted) sample *= 2
            // A very tall strip is kept under what a view may draw
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) * 4 > MAX_BITMAP_BYTES) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private const val CACHE_BYTES = 48 * 1024 * 1024
    private const val LOAD_TIMEOUT_MS = 60_000L
    private const val MAX_BITMAP_BYTES = 40_000_000L
}
