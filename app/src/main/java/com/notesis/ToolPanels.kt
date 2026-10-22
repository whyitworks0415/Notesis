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
import androidx.compose.material3.Text
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

/** The open tool's panel. */
@Composable
internal fun ToolPanelHost(panel: ToolPanel) {
    val close = { Planner.open = null }
    when (panel) {
        ToolPanel.CALCULATOR -> GlassSheet(title = "Calculator", onClose = close, maxWidth = 460.dp) { CalculatorPanel() }
        ToolPanel.TODO -> GlassSheet(title = "To-do", onClose = close) { TodoPanel() }
        ToolPanel.CALENDAR -> GlassSheet(title = "Calendar", onClose = close, maxWidth = 480.dp) { CalendarPanel() }
    }
}

/** The toolbar's and the library's way in: the clock and the three tools in one menu. */
@Composable
internal fun ToolsMenuButton() {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { open = true }, icon = AppIcons.tools, contentDescription = "Tools")
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Clock") },
                leadingIcon = { Icon(Reicons.Clock, contentDescription = null) },
                onClick = { open = false; StudyClock.openPanel() },
            )
            DropdownMenuItem(
                text = { Text("Calculator") },
                leadingIcon = { Icon(Reicons.Calculate, contentDescription = null) },
                onClick = { open = false; Planner.show(ToolPanel.CALCULATOR) },
            )
            DropdownMenuItem(
                text = { Text("To-do") },
                leadingIcon = { Icon(Reicons.Checklist, contentDescription = null) },
                onClick = { open = false; Planner.show(ToolPanel.TODO) },
            )
            DropdownMenuItem(
                text = { Text("Calendar") },
                leadingIcon = { Icon(Reicons.CalendarMonth, contentDescription = null) },
                onClick = { open = false; Planner.show(ToolPanel.CALENDAR) },
            )
        }
    }
}

// ---- calculator ----------------------------------------------------------------------

private enum class CalcMode(val label: String) { CALC("Calculate"), DERIVE("Derivative"), INTEGRATE("Integral") }

/** The calculator's fields, kept across opening and closing it. */
private object CalcState {
    var mode by mutableStateOf(CalcMode.CALC)
    var degrees by mutableStateOf(true)
    var second by mutableStateOf(false)
    var expression by mutableStateOf("")
    var function by mutableStateOf("")
    var point by mutableStateOf("")
    var lower by mutableStateOf("")
    var upper by mutableStateOf("")
    /** Which field the keypad types into: 0 the expression or f(x), 1 the point or lower end, 2 the upper end. */
    var field by mutableStateOf(0)
    var ans by mutableStateOf(0.0)
    val history = mutableStateListOf<Pair<String, String>>()
}

@Composable
private fun CalculatorPanel() {
    val s = CalcState
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        CalcMode.entries.forEach { mode ->
            ClockChip(mode.label, s.mode == mode, Modifier.weight(1f)) { s.mode = mode; s.field = 0 }
        }
    }
    when (s.mode) {
        CalcMode.CALC -> CalcDisplay()
        CalcMode.DERIVE -> DeriveDisplay()
        CalcMode.INTEGRATE -> IntegrateDisplay()
    }
    Keypad(onKey = ::press)
    if (s.mode == CalcMode.CALC && s.history.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            for ((expr, result) in s.history.asReversed()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Ui.Shape.chip)
                        .clickable { s.expression += result.takeIf { it.first().isDigit() || it.first() == '-' } ?: "" }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(expr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(" = $result", style = MaterialTheme.typography.bodyMedium.merge(TabularDigits))
                }
            }
        }
    }
}

@Composable
private fun CalcDisplay() {
    val s = CalcState
    val preview = remember(s.expression, s.degrees, s.ans) {
        if (s.expression.isBlank()) "" else runCatching {
            formatNumber(MathExpr.eval(MathExpr.parse(s.expression), s.degrees, ans = s.ans))
        }.getOrDefault("")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        ClockChip(if (s.degrees) "DEG" else "RAD", selected = false) { s.degrees = !s.degrees }
        Text(
            if (s.degrees) "Angles: degrees (°)" else "Angles: radians",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    DisplayBox(focused = true) {
        Text(
            s.expression.ifEmpty { "0" },
            style = MaterialTheme.typography.headlineSmall.merge(TabularDigits),
            textAlign = TextAlign.End,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (preview.isNotEmpty()) "= $preview" else " ",
            style = MaterialTheme.typography.titleMedium.merge(TabularDigits),
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeriveDisplay() {
    val s = CalcState
    val result = remember(s.function, s.point) { deriveText(s.function, s.point) }
    Field("f(x) =", s.function, focused = s.field == 0) { s.field = 0 }
    Field("x =", s.point, focused = s.field == 1, hint = "Point for the slope (blank for the derivative only)") { s.field = 1 }
    ResultBox(result)
    Text(
        "Derivatives and integrals use radians.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun IntegrateDisplay() {
    val s = CalcState
    val result = remember(s.function, s.lower, s.upper) { integrateText(s.function, s.lower, s.upper) }
    Field("f(x) =", s.function, focused = s.field == 0) { s.field = 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        Box(Modifier.weight(1f)) { Field("Lower a", s.lower, focused = s.field == 1) { s.field = 1 } }
        Box(Modifier.weight(1f)) { Field("Upper b", s.upper, focused = s.field == 2) { s.field = 2 } }
    }
    ResultBox(result)
    Text(
        "Computes ∫ₐᵇ f(x) dx with adaptive Simpson's rule, in radians.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

private fun deriveText(function: String, point: String): List<String> {
    if (function.isBlank()) return listOf("Enter f(x)")
    val f = runCatching { MathExpr.parse(function) }.getOrElse { return listOf(it.message ?: "Can't read the expression") }
    val lines = ArrayList<String>()
    val derived = MathExpr.derivative(f)
    if (derived != null) lines += "f′(x) = " + MathExpr.format(derived)
    if (point.isNotBlank()) {
        val a = runCatching { MathExpr.eval(MathExpr.parse(point), degrees = false, ans = CalcState.ans) }
            .getOrElse { return lines + (it.message ?: "Can't read the x value") }
        val value = derived?.let { runCatching { MathExpr.eval(it, degrees = false, x = a, ans = CalcState.ans) }.getOrNull() }
            ?.takeIf { it.isFinite() }
            ?: runCatching { MathExpr.numericDerivative(f, a, CalcState.ans) }.getOrNull()
        lines += "f′(${formatNumber(a)}) = " + (value?.let(::formatNumber) ?: "Undefined")
        runCatching { MathExpr.eval(f, degrees = false, x = a, ans = CalcState.ans) }.getOrNull()?.let {
            lines += "f(${formatNumber(a)}) = " + formatNumber(it)
        }
    } else if (derived == null) {
        lines += "Can't build a derivative formula for this. Enter an x value to get the slope at that point"
    }
    return lines
}

private fun integrateText(function: String, lower: String, upper: String): List<String> {
    if (function.isBlank()) return listOf("Enter f(x)")
    if (lower.isBlank() || upper.isBlank()) return listOf("Enter the lower and upper bounds")
    return runCatching {
        val f = MathExpr.parse(function)
        val a = MathExpr.eval(MathExpr.parse(lower), degrees = false, ans = CalcState.ans)
        val b = MathExpr.eval(MathExpr.parse(upper), degrees = false, ans = CalcState.ans)
        listOf("∫ = " + formatNumber(MathExpr.integrate(f, a, b, CalcState.ans)))
    }.getOrElse { listOf(it.message ?: "Can't calculate") }
}

@Composable
private fun DisplayBox(focused: Boolean, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Ui.Shape.control)
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) scheme.primary.copy(alpha = 0.6f) else Color.Transparent,
                shape = Ui.Shape.control,
            )
            .padding(horizontal = Ui.Space.l, vertical = Ui.Space.m),
    ) { content() }
}

@Composable
private fun Field(label: String, value: String, focused: Boolean, hint: String = "", onFocus: () -> Unit) {
    Box(Modifier.clip(Ui.Shape.control).clickable(onClick = onFocus)) {
        DisplayBox(focused) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Text(
                value.ifEmpty { hint.ifEmpty { " " } },
                style = MaterialTheme.typography.titleLarge.merge(TabularDigits),
                color = if (value.isEmpty()) MaterialTheme.colorScheme.outline.copy(alpha = 0.6f) else Color.Unspecified,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ResultBox(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Ui.Shape.control)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = Ui.Space.l, vertical = Ui.Space.m),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.hair),
    ) {
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.titleMedium.merge(TabularDigits),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** A keypad key: what it shows and what it does. */
private data class Key(val label: String, val insert: String = label, val kind: Int = KEY_INPUT)

private const val KEY_INPUT = 0
private const val KEY_FUNCTION = 1
private const val KEY_OPERATOR = 2
private const val KEY_ACTION = 3

private val FIRST_KEYS = listOf(
    listOf(Key("2nd", "", KEY_ACTION), Key("sin", "sin(", KEY_FUNCTION), Key("cos", "cos(", KEY_FUNCTION),
        Key("tan", "tan(", KEY_FUNCTION), Key("(", "(", KEY_OPERATOR), Key(")", ")", KEY_OPERATOR)),
    listOf(Key("√", "√(", KEY_FUNCTION), Key("xʸ", "^", KEY_OPERATOR), Key("x²", "^2", KEY_OPERATOR),
        Key("ln", "ln(", KEY_FUNCTION), Key("log", "log(", KEY_FUNCTION), Key("logₙ", "logb(", KEY_FUNCTION)),
    listOf(Key("7"), Key("8"), Key("9"), Key("÷", "÷", KEY_OPERATOR), Key("⌫", "", KEY_ACTION), Key("AC", "", KEY_ACTION)),
    listOf(Key("4"), Key("5"), Key("6"), Key("×", "×", KEY_OPERATOR), Key("π", "π", KEY_FUNCTION), Key("e", "e", KEY_FUNCTION)),
    listOf(Key("1"), Key("2"), Key("3"), Key("−", "-", KEY_OPERATOR), Key("x", "x", KEY_FUNCTION), Key(",", ",", KEY_OPERATOR)),
    listOf(Key("0"), Key("."), Key("Ans", "Ans", KEY_FUNCTION), Key("+", "+", KEY_OPERATOR), Key("x!", "!", KEY_OPERATOR),
        Key("=", "", KEY_ACTION)),
)

/** With 2nd held: the inverse and hyperbolic functions, and the rarer ones. */
private val SECOND_KEYS = listOf(
    listOf(Key("2nd", "", KEY_ACTION), Key("sin⁻¹", "asin(", KEY_FUNCTION), Key("cos⁻¹", "acos(", KEY_FUNCTION),
        Key("tan⁻¹", "atan(", KEY_FUNCTION), Key("(", "(", KEY_OPERATOR), Key(")", ")", KEY_OPERATOR)),
    listOf(Key("∛", "cbrt(", KEY_FUNCTION), Key("eˣ", "exp(", KEY_FUNCTION), Key("10ˣ", "10^", KEY_OPERATOR),
        Key("sinh", "sinh(", KEY_FUNCTION), Key("cosh", "cosh(", KEY_FUNCTION), Key("tanh", "tanh(", KEY_FUNCTION)),
    listOf(Key("log₂", "log2(", KEY_FUNCTION), Key("|x|", "abs(", KEY_FUNCTION), Key("%", "%", KEY_OPERATOR),
        Key("÷", "÷", KEY_OPERATOR), Key("⌫", "", KEY_ACTION), Key("AC", "", KEY_ACTION)),
) + FIRST_KEYS.drop(3)

@Composable
private fun Keypad(onKey: (Key) -> Unit) {
    val rows = if (CalcState.second) SECOND_KEYS else FIRST_KEYS
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                row.forEach { key -> KeyButton(key, Modifier.weight(1f)) { onKey(key) } }
            }
        }
    }
}

@Composable
private fun KeyButton(key: Key, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val equals = key.label == "="
    val secondOn = key.label == "2nd" && CalcState.second
    val background = when {
        equals || secondOn -> scheme.primary
        key.kind == KEY_ACTION -> scheme.secondaryContainer
        key.kind == KEY_OPERATOR -> scheme.onSurface.copy(alpha = 0.1f)
        key.kind == KEY_FUNCTION -> scheme.onSurface.copy(alpha = 0.07f)
        else -> scheme.onSurface.copy(alpha = 0.04f)
    }
    val content = when {
        equals || secondOn -> scheme.onPrimary
        key.kind == KEY_ACTION -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    Box(
        modifier
            .height(46.dp)
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
                style = if (key.kind == KEY_INPUT) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
            )
        }
    }
}

private fun press(key: Key) {
    val s = CalcState
    when (key.label) {
        "2nd" -> {
            s.second = !s.second
            return
        }
        "AC" -> {
            setField("")
            return
        }
        "⌫" -> {
            setField(backspace(currentField()))
            return
        }
        "=" -> {
            if (s.mode == CalcMode.CALC) commit() else s.field = (s.field + 1) % (if (s.mode == CalcMode.INTEGRATE) 3 else 2)
            return
        }
    }
    setField(currentField() + key.insert)
    // A second function was for one key, as on a calculator.
    if (s.second && key.kind == KEY_FUNCTION) s.second = false
}

private fun currentField(): String = with(CalcState) {
    when (mode) {
        CalcMode.CALC -> expression
        CalcMode.DERIVE -> if (field == 1) point else function
        CalcMode.INTEGRATE -> when (field) {
            1 -> lower
            2 -> upper
            else -> function
        }
    }
}

private fun setField(value: String) = with(CalcState) {
    when (mode) {
        CalcMode.CALC -> expression = value
        CalcMode.DERIVE -> if (field == 1) point = value else function = value
        CalcMode.INTEGRATE -> when (field) {
            1 -> lower = value
            2 -> upper = value
            else -> function = value
        }
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
