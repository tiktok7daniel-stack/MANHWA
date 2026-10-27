package eu.kanade.presentation.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.sharedCover
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

/**
 * MANHWA: the library as books standing on shelves. Each shelf scrolls on its own; the books turn towards the middle
 * of the screen as they pass (a book at the edge leans away), sit on a lit plank, and show how far you are on a thin
 * bar along their spine.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryShelf(
    items: List<LibraryItem>,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
) {
    val shelves = items.chunked(BOOKS_PER_SHELF)
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(shelves, key = { i, _ -> "shelf$i" }) { _, books ->
            val row = rememberLazyListState()
            Column(Modifier.padding(top = 18.dp)) {
                LazyRow(
                    state = row,
                    contentPadding = PaddingValues(horizontal = 22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(books, key = { _, it -> it.libraryManga.manga.id }) { index, item ->
                        val manga = item.libraryManga.manga
                        val selected = manga.id in selection
                        val lift by animateFloatAsState(if (selected) 1f else 0f, label = "lift")
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .width(BOOK_WIDTH.dp)
                                .graphicsLayer {
                                    // How far this book is from the middle of the shelf, -1 (left edge) to 1 (right)
                                    val info = row.layoutInfo
                                    val book = info.visibleItemsInfo.firstOrNull { it.index == index }
                                    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                    val off = if (book == null || middle == 0f) {
                                        0f
                                    } else {
                                        ((book.offset + book.size / 2f - middle) / middle).coerceIn(-1f, 1f)
                                    }
                                    rotationY = -off * 22f
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                    cameraDistance = 10 * density
                                    translationY = -lift * 14.dp.toPx()
                                    val scale = 1f - kotlin.math.abs(off) * 0.06f
                                    scaleX = scale
                                    scaleY = scale
                                },
                        ) {
                            Column {
                                Box {
                                    MangaCover.Book(
                                        data = MangaCoverModel(
                                            mangaId = manga.id,
                                            sourceId = manga.source,
                                            isMangaFavorite = manga.favorite,
                                            url = manga.thumbnailUrl,
                                            lastModified = manga.coverLastModified,
                                        ),
                                        shape = RoundedCornerShape(topStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 3.dp),
                                        modifier = Modifier
                                            .sharedCover(manga.id)
                                            .then(
                                                if (selected) {
                                                    Modifier.border(2.dp, accent, RoundedCornerShape(8.dp))
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .combinedClickable(
                                                onClick = { onClick(item.libraryManga) },
                                                onLongClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onLongClick(item.libraryManga)
                                                },
                                            ),
                                    )
                                    // The spine: a dark fold on the left edge, and the reading progress up it
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.horizontalGradient(
                                                    0f to Color.Black.copy(alpha = 0.45f),
                                                    0.06f to Color.Black.copy(alpha = 0.1f),
                                                    0.1f to Color.Transparent,
                                                    0.85f to Color.Transparent,
                                                    1f to Color.White.copy(alpha = 0.08f),
                                                ),
                                            ),
                                    )
                                    val lm = item.libraryManga
                                    if (lm.hasFreshChapter()) {
                                        NewChapterGlow(Modifier.align(androidx.compose.ui.Alignment.TopEnd))
                                    }
                                    if (lm.totalChapters > 0) {
                                        val done = (lm.readCount.toFloat() / lm.totalChapters).coerceIn(0f, 1f)
                                        Box(
                                            Modifier
                                                .matchParentSize()
                                                .drawBehind {
                                                    val w = 3.dp.toPx()
                                                    drawRect(Color.Black.copy(alpha = 0.4f), size = Size(w, size.height))
                                                    drawRect(
                                                        accent,
                                                        topLeft = Offset(0f, size.height * (1 - done)),
                                                        size = Size(w, size.height * done),
                                                    )
                                                },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = manga.title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }
                // The plank the books stand on, lit from above in the accent colour
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .height(16.dp)
                        .drawBehind {
                            drawRoundRect(
                                Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color(0xFF1A1A20), Color(0xFF0C0C10))),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                            drawRect(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                                topLeft = Offset(0f, size.height),
                                size = Size(size.width, 14.dp.toPx()),
                            )
                            drawLine(
                                Color.White.copy(alpha = 0.25f),
                                Offset(6.dp.toPx(), 1f),
                                Offset(size.width - 6.dp.toPx(), 1f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

private const val BOOKS_PER_SHELF = 8
private const val BOOK_WIDTH = 104
