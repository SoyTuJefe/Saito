package ani.saikou.media.manga

import ani.saikou.media.Author
import java.io.Serializable

data class Manga(
    var totalChapters: Int? = null,
    var selectedChapter: String? = null,
    var chapters: MutableMap<String, MangaChapter>? = null,
    var slug: String? = null,
    var author: Author? = null,
) : Serializable