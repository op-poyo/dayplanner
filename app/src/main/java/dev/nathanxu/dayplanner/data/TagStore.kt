package dev.nathanxu.dayplanner.data

import android.content.Context
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your tags and their colours. Kept in SharedPreferences as JSON (a short list; no need for a
 * table). Starts with the built-in tags, whose keys match the old categories.
 */
class TagStore(context: Context) {
    private val sp = context.getSharedPreferences("tags", Context.MODE_PRIVATE)
    private val _tags = MutableStateFlow(load())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()
    val current: List<Tag> get() = _tags.value

    private fun load(): List<Tag> {
        val raw = sp.getString(KEY, null) ?: return Tags.DEFAULTS
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Tag(o.getString("k"), o.getString("n"), o.getInt("c"))
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: Tags.DEFAULTS
    }

    private fun save(list: List<Tag>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.key).put("n", it.name).put("c", it.color)) }
        sp.edit().putString(KEY, arr.toString()).apply()
        _tags.value = list
    }

    /** Finds the tag for "#raw", creating it with the next free colour if it's new. */
    fun resolveOrCreate(raw: String): Pair<Tag, Boolean> {
        Tags.resolve(raw, current)?.let { return it to false }
        return add(raw.trim().replaceFirstChar(Char::uppercase), null) to true
    }

    fun add(name: String, color: Int?): Tag {
        val clean = name.trim().take(20)
        Tags.resolve(clean, current)?.let { return it }
        var key = Tags.keyFor(clean).ifEmpty { "TAG" }
        var n = 2
        while (current.any { it.key == key }) key = Tags.keyFor(clean) + "_" + n++
        val tag = Tag(key, clean, color ?: Tags.nextColor(current))
        save(current + tag)
        return tag
    }

    fun update(tag: Tag) = save(current.map { if (it.key == tag.key) tag else it })

    /** Rows using a deleted tag show as Other. The Other tag itself can't be deleted. */
    fun delete(key: String) {
        if (key == Tags.OTHER) return
        save(current.filterNot { it.key == key })
    }

    private companion object {
        const val KEY = "tags_v1"
    }
}
