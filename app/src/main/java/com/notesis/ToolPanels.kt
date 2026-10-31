package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/**
 * The calculator, the clock, the calendar and the to-do list: one panel with
 * a tab for each, grown out of the point that opened it and folded back into
 * it, like every other panel off the bar.
 */
@Composable
internal fun ToolPanelHost(panel: ToolPanel) {
    val state = rememberToolPanelState()
    val close = { state.close { Planner.open = null } }
    ToolPanel(
        title = "Tools",
        state = state,
        onOutside = close,
        maxWidth = 480.dp,
        heading = {
            LiquidSegmentedControl(
                segments = ToolPanel.entries.map { it.label },
                selectedIndex = panel.ordinal,
                onSelected = { Planner.show(ToolPanel.entries[it]) },
                useLiquidGlass = LocalSkin.current.isRefractive,
                modifier = Modifier.weight(1f),
            )
        },
        actions = {
            IconButton(onClick = close) { Icon(Reicons.Close, contentDescription = "Close") }
        },
    ) {
        when (panel) {
            ToolPanel.CALCULATOR -> CalculatorPanel()
            ToolPanel.CLOCK -> ClockSection()
            ToolPanel.CALENDAR -> CalendarPanel()
            ToolPanel.TODO -> TodoPanel()
        }
    }
}

/** The toolbar's and the library's way in: the clock and the three tools in one panel. */
@Composable
internal fun ToolsMenuButton() {
    AppIconButton(onClick = { Planner.show(Planner.lastPanel) }, icon = AppIcons.tools, contentDescription = "Tools")
}

// ---- calculator ----------------------------------------------------------------------

/** The calculator's display and history, kept across opening and closing it. */
private object CalcState {
    var degrees by mutableStateOf(true)
    /** Whether the row of functions is out above the digits. */
    var functions by mutableStateOf(false)
    var expression by mutableStateOf("")
    var ans by mutableStateOf(0.0)
    val history = mutableStateListOf<Pair<String, String>>()
}

/**
 * A plain calculator: the sum and its answer, the digits and the four
 * operations. The functions - sin, √, powers, brackets - are one key away,
 * a short block above the digits, and go again when that key is let go of.
 */
@Composable
private fun CalculatorPanel() {
    val s = CalcState
    val preview = remember(s.expression, s.degrees, s.ans) {
        if (s.expression.isBlank()) "" else runCatching {
            formatNumber(MathExpr.eval(MathExpr.parse(s.expression), s.degrees, ans = s.ans))
        }.getOrDefault("")
    }
    DisplayBox {
        // The last sum, small, above the one being typed.
        s.history.lastOrNull()?.let { (expr, result) ->
            Text(
                "$expr = $result",
                style = MaterialTheme.typography.bodyMedium.merge(TabularDigits),
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            s.expression.ifEmpty { "0" },
            style = MaterialTheme.typography.headlineMedium.merge(TabularDigits),
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (preview.isNotEmpty() && preview != s.expression) "= $preview" else " ",
            style = MaterialTheme.typography.titleMedium.merge(TabularDigits),
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (s.functions) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            ClockChip(if (s.degrees) "DEG" else "RAD", selected = false) { s.degrees = !s.degrees }
            Text(
                if (s.degrees) "Angles in degrees" else "Angles in radians",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        KeyRows(FUNCTION_KEYS)
    }
    KeyRows(DIGIT_KEYS)
}

@Composable
private fun DisplayBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Ui.Shape.control)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = Ui.Space.l, vertical = Ui.Space.m),
    ) { content() }
}

/** A keypad key: what it shows and what it does. */
private data class Key(val label: String, val insert: String = label, val kind: Int = KEY_INPUT)

private const val KEY_INPUT = 0
private const val KEY_FUNCTION = 1
private const val KEY_OPERATOR = 2
private const val KEY_ACTION = 3

/** Four across, as on any calculator; "f(x)" brings the functions out above. */
private val DIGIT_KEYS = listOf(
    listOf(Key("AC", "", KEY_ACTION), Key("⌫", "", KEY_ACTION), Key("%", "%", KEY_OPERATOR), Key("÷", "÷", KEY_OPERATOR)),
    listOf(Key("7"), Key("8"), Key("9"), Key("×", "×", KEY_OPERATOR)),
    listOf(Key("4"), Key("5"), Key("6"), Key("−", "-", KEY_OPERATOR)),
    listOf(Key("1"), Key("2"), Key("3"), Key("+", "+", KEY_OPERATOR)),
    listOf(Key("f(x)", "", KEY_ACTION), Key("0"), Key("."), Key("=", "", KEY_ACTION)),
)

private val FUNCTION_KEYS = listOf(
    listOf(Key("(", "(", KEY_OPERATOR), Key(")", ")", KEY_OPERATOR), Key("xʸ", "^", KEY_OPERATOR), Key("√", "√(", KEY_FUNCTION)),
    listOf(Key("sin", "sin(", KEY_FUNCTION), Key("cos", "cos(", KEY_FUNCTION), Key("tan", "tan(", KEY_FUNCTION), Key("π", "π", KEY_FUNCTION)),
    listOf(Key("ln", "ln(", KEY_FUNCTION), Key("log", "log(", KEY_FUNCTION), Key("x²", "^2", KEY_OPERATOR), Key("Ans", "Ans", KEY_FUNCTION)),
)

@Composable
private fun KeyRows(rows: List<List<Key>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                row.forEach { key -> KeyButton(key, Modifier.weight(1f)) { press(key) } }
            }
        }
    }
}

@Composable
private fun KeyButton(key: Key, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val equals = key.label == "="
    val functionsOut = key.label == "f(x)" && CalcState.functions
    val background = when {
        equals || functionsOut -> scheme.primary
        key.kind == KEY_ACTION -> scheme.secondaryContainer
        key.kind == KEY_OPERATOR -> scheme.onSurface.copy(alpha = 0.1f)
        key.kind == KEY_FUNCTION -> scheme.onSurface.copy(alpha = 0.07f)
        else -> scheme.onSurface.copy(alpha = 0.04f)
    }
    val content = when {
        equals || functionsOut -> scheme.onPrimary
        key.kind == KEY_ACTION -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    Box(
        modifier
            .height(52.dp)
            .clip(Ui.Shape.control)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (key.label == "⌫") {
            Icon(Reicons.Backspace, contentDescription = "Backspace", tint = content, modifier = Modifier.size(Ui.Icon.small))
        } else {
            Text(
                key.label,
                style = if (key.kind == KEY_INPUT) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = content,
                maxLines = 1,
            )
        }
    }
}

private fun press(key: Key) {
    val s = CalcState
    when (key.label) {
        "f(x)" -> s.functions = !s.functions
        "AC" -> s.expression = ""
        "⌫" -> s.expression = backspace(s.expression)
        "=" -> commit()
        else -> s.expression += key.insert
    }
}

/** One key back: a whole function name with its bracket goes at once, as it came. */
internal fun backspace(text: String): String {
    if (text.isEmpty()) return text
    for (name in BACKSPACE_TOKENS) if (text.endsWith(name)) return text.dropLast(name.length)
    return text.dropLast(1)
}

private val BACKSPACE_TOKENS = (MathExpr.FUNCTIONS.map { "$it(" } + listOf("√(", "Ans")).sortedByDescending { it.length }

private fun commit() {
    val s = CalcState
    if (s.expression.isBlank()) return
    val result = runCatching { MathExpr.eval(MathExpr.parse(s.expression), s.degrees, ans = s.ans) }
    result.onSuccess { value ->
        val shown = formatNumber(value)
        s.history += s.expression to shown
        while (s.history.size > HISTORY) s.history.removeAt(0)
        // The answer stays on the display to carry on from, and as Ans.
        if (value.isFinite()) {
            s.ans = value
            s.expression = shown
        }
    }.onFailure { error ->
        s.history += s.expression to (error.message ?: "Error")
        while (s.history.size > HISTORY) s.history.removeAt(0)
    }
}

private const val HISTORY = 6

// ---- checklists ------------------------------------------------------------------------

@Composable
private fun TodoPanel() {
    val items = Planner.todo
    val left = items.count { !it.done }
    Text(
        if (items.isEmpty()) "Write down your to-dos" else "${left} left · ${items.size - left} done",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
    )
    AddRow(placeholder = "Add a to-do") { Planner.addTodo(it) }
    Checklist(
        items = items,
        onToggle = Planner::toggleTodo,
        onRename = Planner::renameTodo,
        onRemove = Planner::removeTodo,
    )
    if (items.any { it.done }) {
        ClockButton("Clear completed", primary = false, modifier = Modifier.fillMaxWidth()) { Planner.clearDoneTodo() }
    }
}

/** A line to type into, added with the keyboard's done key or the + button. */
@Composable
private fun AddRow(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val add = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Ui.Shape.control)
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .padding(start = Ui.Space.l, end = Ui.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.outline)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = add, enabled = text.isNotBlank()) {
            Icon(Reicons.Add, contentDescription = "Add", tint = if (text.isNotBlank()) scheme.primary else scheme.outline)
        }
    }
}

/**
 * Items to tick: what is left first, then what is done, struck through.
 * Tapping the text edits it in place; emptying it removes the item.
 */
@Composable
private fun Checklist(
    items: List<CheckItem>,
    onToggle: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onRemove: (Long) -> Unit,
) {
    var editing by remember { mutableStateOf<Long?>(null) }
    val ordered = items.filter { !it.done } + items.filter { it.done }
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.hair)) {
        ordered.forEach { item ->
            CheckRow(
                item = item,
                editing = editing == item.id,
                onToggle = { onToggle(item.id) },
                onEdit = { editing = item.id },
                onRename = { text -> onRename(item.id, text); editing = null },
                onRemove = { onRemove(item.id) },
            )
        }
    }
}

@Composable
private fun CheckRow(
    item: CheckItem,
    editing: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(Ui.Shape.chip)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(item.done, onToggle)
        Spacer(Modifier.width(Ui.Space.m))
        if (editing) {
            var text by remember(item.id) { mutableStateOf(item.text) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(text) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { onRename(text) }) { Icon(Reicons.Check, contentDescription = "Save") }
        } else {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = if (item.done) scheme.outline else scheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 10.dp),
            )
            IconButton(onClick = onRemove) {
                Icon(Reicons.Close, contentDescription = "Delete", tint = scheme.outline, modifier = Modifier.size(Ui.Icon.small))
            }
        }
    }
}

/** A round box that fills in with a tick. */
@Composable
private fun CheckMark(done: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (done) scheme.primary else Color.Transparent)
            .border(2.dp, if (done) scheme.primary else scheme.outline, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Reicons.Check, contentDescription = "Done", tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
    }
}

// ---- calendar ---------------------------------------------------------------------------

private object CalendarState {
    var month by mutableStateOf(LocalDate.now().withDayOfMonth(1))
    var selected by mutableStateOf(LocalDate.now())
}

@Composable
private fun CalendarPanel() {
    val c = CalendarState
    val today = java.time.Instant.ofEpochMilli(StudyClock.now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val days = Planner.days
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { c.month = c.month.minusMonths(1) }) { Icon(Reicons.ChevronLeft, contentDescription = "Previous month") }
        Text(
            "${c.month.month.getDisplayName(DateTextStyle.FULL, Locale.ENGLISH)} ${c.month.year}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { c.month = c.month.plusMonths(1) }) { Icon(Reicons.ChevronRight, contentDescription = "Next month") }
        ClockChip("Today", selected = false) {
            c.month = today.withDayOfMonth(1)
            c.selected = today
        }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, name ->
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = weekdayColor(i) ?: MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val grid = monthGrid(c.month)
    // Five rows when the month fits in five; six only when it needs them.
    val rows = if (grid[35].month != c.month.month) 5 else 6
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.hair)) {
        for (week in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.hair)) {
                for (dayIndex in 0 until 7) {
                    val date = grid[week * 7 + dayIndex]
                    DayCell(
                        date = date,
                        inMonth = date.month == c.month.month,
                        isToday = date == today,
                        isSelected = date == c.selected,
                        items = days[date.toString()].orEmpty(),
                        weekday = dayIndex,
                        modifier = Modifier.weight(1f),
                    ) {
                        c.selected = date
                        if (date.month != c.month.month) c.month = date.withDayOfMonth(1)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val key = c.selected.toString()
    val items = days[key].orEmpty()
    Text(
        c.selected.dayOfWeek.getDisplayName(DateTextStyle.FULL, Locale.ENGLISH) + ", " +
            c.selected.month.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH) + " ${c.selected.dayOfMonth}" +
            if (items.isNotEmpty()) " · ${items.count { it.done }}/${items.size}" else "",
        style = MaterialTheme.typography.titleMedium,
    )
    // Keyed by day, so a half-typed line stays with the day it was typed for.
    androidx.compose.runtime.key(key) {
        AddRow(placeholder = "Add a to-do for this day") { Planner.addToDay(key, it) }
    }
    if (items.isEmpty()) {
        Text("Nothing written down for this day", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
    Checklist(
        items = items,
        onToggle = { Planner.toggleDay(key, it) },
        onRename = { id, text -> Planner.renameDay(key, id, text) },
        onRemove = { Planner.removeFromDay(key, it) },
    )
}

private fun weekdayColor(index: Int): Color? = when (index) {
    0 -> Color(0xFFE5484D)
    6 -> Color(0xFF3B82F6)
    else -> null
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    items: List<CheckItem>,
    weekday: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .aspectRatio(1f)
            .clip(Ui.Shape.chip)
            .background(
                when {
                    isSelected -> scheme.primary
                    isToday -> scheme.primaryContainer
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .alpha(if (inMonth) 1f else 0.35f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
            color = when {
                isSelected -> scheme.onPrimary
                isToday -> scheme.onPrimaryContainer
                else -> weekdayColor(weekday) ?: scheme.onSurface
            },
        )
        Spacer(Modifier.height(3.dp))
        // A dot for a day with something on it: hollow while anything is left, filled when all is done.
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .then(
                    when {
                        items.isEmpty() -> Modifier
                        items.all { it.done } -> Modifier.background(if (isSelected) scheme.onPrimary else StudyGreen)
                        else -> Modifier.background(if (isSelected) scheme.onPrimary else scheme.primary)
                    },
                ),
        )
    }
}
