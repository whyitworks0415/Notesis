package com.notesis

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One line of a checklist. */
internal data class CheckItem(val id: Long, val text: String, val done: Boolean = false)

/** The tools panel's three tools besides the clock. */
internal enum class ToolPanel { CALCULATOR, TODO, CALENDAR }

/**
 * The to-do list and the calendar's day lists, kept apart: the to-do list is
 * one running list, and each calendar day has a checklist of its own.
 * Both are kept on the device and written as they change.
 */
internal object Planner {
    private var prefs: SharedPreferences? = null

    /** Which tool panel is open, if any. */
    var open by mutableStateOf<ToolPanel?>(null)

    var todo by mutableStateOf<List<CheckItem>>(emptyList())
        private set

    /** Each day's checklist, keyed by ISO date; days with nothing on them are absent. */
    var days by mutableStateOf<Map<String, List<CheckItem>>>(emptyMap())
        private set

    private var lastId = 0L

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("planner", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            todo = itemsOf(JSONArray(p.getString("todo", "[]")))
            val json = JSONObject(p.getString("days", "{}") ?: "{}")
            days = json.keys().asSequence().associateWith { itemsOf(json.getJSONArray(it)) }
                .filterValues { it.isNotEmpty() }
        }
    }

    fun show(panel: ToolPanel) {
        open = panel
    }

    // ---- the to-do list -------------------------------------------------------------

    fun addTodo(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        todo = todo + CheckItem(nextId(), clean)
        saveTodo()
    }

    fun toggleTodo(id: Long) = updateTodo { list -> list.map { if (it.id == id) it.copy(done = !it.done) else it } }

    fun renameTodo(id: Long, text: String) = updateTodo { list ->
        val clean = text.trim()
        if (clean.isEmpty()) list.filterNot { it.id == id } else list.map { if (it.id == id) it.copy(text = clean) else it }
    }

    fun removeTodo(id: Long) = updateTodo { list -> list.filterNot { it.id == id } }

    fun clearDoneTodo() = updateTodo { list -> list.filterNot { it.done } }

    private fun updateTodo(change: (List<CheckItem>) -> List<CheckItem>) {
        todo = change(todo)
        saveTodo()
    }

    // ---- the calendar's days ----------------------------------------------------------

    fun dayItems(date: String): List<CheckItem> = days[date].orEmpty()

    fun addToDay(date: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        updateDay(date) { it + CheckItem(nextId(), clean) }
    }

    fun toggleDay(date: String, id: Long) = updateDay(date) { list ->
        list.map { if (it.id == id) it.copy(done = !it.done) else it }
    }

    fun renameDay(date: String, id: Long, text: String) = updateDay(date) { list ->
        val clean = text.trim()
        if (clean.isEmpty()) list.filterNot { it.id == id } else list.map { if (it.id == id) it.copy(text = clean) else it }
    }

    fun removeFromDay(date: String, id: Long) = updateDay(date) { list -> list.filterNot { it.id == id } }

    private fun updateDay(date: String, change: (List<CheckItem>) -> List<CheckItem>) {
        val next = change(dayItems(date))
        days = if (next.isEmpty()) days - date else days + (date to next)
        saveDays()
    }

    // ---- storage ------------------------------------------------------------------------

    /** Ids from the clock, made strictly increasing so two in one millisecond still differ. */
    private fun nextId(): Long {
        val now = System.currentTimeMillis()
        lastId = if (now > lastId) now else lastId + 1
        return lastId
    }

    private fun saveTodo() {
        prefs?.edit()?.putString("todo", jsonOf(todo).toString())?.apply()
    }

    private fun saveDays() {
        val json = JSONObject()
        for ((date, items) in days) if (items.isNotEmpty()) json.put(date, jsonOf(items))
        prefs?.edit()?.putString("days", json.toString())?.apply()
    }

    private fun jsonOf(items: List<CheckItem>) = JSONArray(items.map {
        JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)
    })

    private fun itemsOf(array: JSONArray): List<CheckItem> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        CheckItem(o.optLong("id"), o.optString("text"), o.optBoolean("done"))
    }.also { items -> items.maxOfOrNull { it.id }?.let { if (it > lastId) lastId = it } }
}

/** The 6 weeks shown for [month] (any day in it), Sunday first, as ISO dates; days outside the month included. */
internal fun monthGrid(month: java.time.LocalDate): List<java.time.LocalDate> {
    val first = month.withDayOfMonth(1)
    // DayOfWeek: Monday 1 .. Sunday 7; the grid starts on the Sunday on or before the 1st.
    val back = first.dayOfWeek.value % 7
    val start = first.minusDays(back.toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
