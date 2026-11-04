package fr.offlinetune.core.library

import java.text.Normalizer

enum class SortKey(val label: String) {
    ADDED("Date d'ajout"), TITLE("Titre"), ARTIST("Artiste"), TRACK("N° de piste"),
    FILENAME("Nom du fichier"), LOCATION("Emplacement"), PLAYS("Nombre de lectures"),
}

data class SortFields(
    val title: String,
    val artist: String,
    val track: Int?,
    val fileName: String,
    val location: String,
    val added: Long,
    val plays: Int,
)

object LibrarySort {
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase().trim()

    /** Stable sort; [desc] reverses the main key only (ties keep title order). */
    fun <T> sort(list: List<T>, key: SortKey, desc: Boolean, fields: (T) -> SortFields): List<T> {
        val byTitle = compareBy<T> { norm(fields(it).title) }
        val main: Comparator<T> = when (key) {
            SortKey.ADDED -> compareBy { fields(it).added }
            SortKey.TITLE -> compareBy { norm(fields(it).title) }
            SortKey.ARTIST -> compareBy { norm(fields(it).artist) }
            SortKey.TRACK -> compareBy<T> { fields(it).track == null }.thenBy { fields(it).track ?: 0 }
            SortKey.FILENAME -> compareBy { norm(fields(it).fileName) }
            SortKey.LOCATION -> compareBy<T> { norm(fields(it).location) }.thenBy { norm(fields(it).fileName) }
            SortKey.PLAYS -> compareBy { fields(it).plays }
        }
        val ordered = if (desc) main.reversed() else main
        return list.sortedWith(ordered.then(byTitle))
    }

    /** Queue when a track is touched: that track and the ones after it, never the ones before. */
    fun <T> queueFrom(list: List<T>, index: Int): List<T> = if (index in list.indices) list.drop(index) else emptyList()

    /** Selected items in the order the user selected them (items no longer present are skipped). */
    fun <T> inSelectionOrder(selectedIds: List<String>, all: List<T>, id: (T) -> String): List<T> {
        val byId = all.associateBy(id)
        return selectedIds.mapNotNull { byId[it] }
    }
}
