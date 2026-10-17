package eu.kanade.presentation.manga

enum class DownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
    BOOKMARKED_CHAPTERS,

    // MANHWA: stop everything of this work that is waiting to download (and be translated after)
    CANCEL_QUEUED,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
    ADD_TO_LIBRARY,
}

enum class MangaScreenItem {
    INFO_BOX,
    ACTION_ROW,
    DESCRIPTION_WITH_TAG,
    CHAPTER_HEADER,
    CHAPTER,
}
