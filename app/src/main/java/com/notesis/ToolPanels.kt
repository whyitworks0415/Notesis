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
        ToolPanel.CALCULATOR -> GlassSheet(title = "계산기", onClose = close, maxWidth = 460.dp) { CalculatorPanel() }
        ToolPanel.TODO -> GlassSheet(title = "할 일", onClose = close) { TodoPanel() }
        ToolPanel.CALENDAR -> GlassSheet(title = "캘린더", onClose = close, maxWidth = 480.dp) { CalendarPanel() }
    }
}

/** The toolbar's and the library's way in: the clock and the three tools in one menu. */
@Composable
internal fun ToolsMenuButton() {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Reicons.Calculate, contentDescription = "도구") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("시계") },
                leadingIcon = { Icon(Reicons.Clock, contentDescription = null) },
                onClick = { open = false; StudyClock.openPanel() },
            )
            DropdownMenuItem(
                text = { Text("계산기") },
                leadingIcon = { Icon(Reicons.Calculate, contentDescription = null) },
                onClick = { open = false; Planner.show(ToolPanel.CALCULATOR) },
            )
            DropdownMenuItem(
                text = { Text("할 일") },
                leadingIcon = { Icon(Reicons.Checklist, contentDescription = null) },
                onClick = { open = false; Planner.show(ToolPanel.TODO) },
            )
            DropdownMenuItem(
                text = { Text("캘린더") },
                leadingIcon = { Icon(Reicons.CalendarMonth, contentDescription = null) },
                onClick = { open = false; Planner.show(ToolPanel.CALENDAR) },
            )
        }
    }
}

// ---- calculator ----------------------------------------------------------------------

private enum class CalcMode(val label: String) { CALC("계산"), DERIVE("미분"), INTEGRATE("적분") }

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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((expr, result) in s.history.asReversed()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockChip(if (s.degrees) "DEG" else "RAD", selected = false) { s.degrees = !s.degrees }
        Text(
            if (s.degrees) "각도: 도(°)" else "각도: 라디안",
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
    Field("x =", s.point, focused = s.field == 1, hint = "기울기를 볼 점 (비워 두면 도함수만)") { s.field = 1 }
    ResultBox(result)
    Text(
        "미분과 적분은 라디안 기준입니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun IntegrateDisplay() {
    val s = CalcState
    val result = remember(s.function, s.lower, s.upper) { integrateText(s.function, s.lower, s.upper) }
    Field("f(x) =", s.function, focused = s.field == 0) { s.field = 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Field("아래끝 a", s.lower, focused = s.field == 1) { s.field = 1 } }
        Box(Modifier.weight(1f)) { Field("위끝 b", s.upper, focused = s.field == 2) { s.field = 2 } }
    }
    ResultBox(result)
    Text(
        "∫ₐᵇ f(x) dx를 적응형 심프슨 공식으로 계산합니다. 라디안 기준입니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

private fun deriveText(function: String, point: String): List<String> {
    if (function.isBlank()) return listOf("f(x)를 입력하세요")
    val f = runCatching { MathExpr.parse(function) }.getOrElse { return listOf(it.message ?: "식을 읽을 수 없습니다") }
    val lines = ArrayList<String>()
    val derived = MathExpr.derivative(f)
    if (derived != null) lines += "f′(x) = " + MathExpr.format(derived)
    if (point.isNotBlank()) {
        val a = runCatching { MathExpr.eval(MathExpr.parse(point), degrees = false, ans = CalcState.ans) }
            .getOrElse { return lines + (it.message ?: "x 값을 읽을 수 없습니다") }
        val value = derived?.let { runCatching { MathExpr.eval(it, degrees = false, x = a, ans = CalcState.ans) }.getOrNull() }
            ?.takeIf { it.isFinite() }
            ?: runCatching { MathExpr.numericDerivative(f, a, CalcState.ans) }.getOrNull()
        lines += "f′(${formatNumber(a)}) = " + (value?.let(::formatNumber) ?: "정의되지 않음")
        runCatching { MathExpr.eval(f, degrees = false, x = a, ans = CalcState.ans) }.getOrNull()?.let {
            lines += "f(${formatNumber(a)}) = " + formatNumber(it)
        }
    } else if (derived == null) {
        lines += "이 식은 도함수 식을 만들 수 없습니다. x 값을 넣으면 그 점의 기울기를 구합니다"
    }
    return lines
}

private fun integrateText(function: String, lower: String, upper: String): List<String> {
    if (function.isBlank()) return listOf("f(x)를 입력하세요")
    if (lower.isBlank() || upper.isBlank()) return listOf("아래끝과 위끝을 입력하세요")
    return runCatching {
        val f = MathExpr.parse(function)
        val a = MathExpr.eval(MathExpr.parse(lower), degrees = false, ans = CalcState.ans)
        val b = MathExpr.eval(MathExpr.parse(upper), degrees = false, ans = CalcState.ans)
        listOf("∫ = " + formatNumber(MathExpr.integrate(f, a, b, CalcState.ans)))
    }.getOrElse { listOf(it.message ?: "계산할 수 없습니다") }
}

@Composable
private fun DisplayBox(focused: Boolean, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) scheme.primary.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun Field(label: String, value: String, focused: Boolean, hint: String = "", onFocus: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onFocus)) {
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
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (key.label == "⌫") {
            Icon(Reicons.Backspace, contentDescription = "지우기", tint = content, modifier = Modifier.size(20.dp))
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
        s.history += s.expression to (error.message ?: "오류")
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
        if (items.isEmpty()) "할 일을 적어 두세요" else "남은 일 ${left}개 · 완료 ${items.size - left}개",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
    )
    AddRow(placeholder = "할 일 추가") { Planner.addTodo(it) }
    Checklist(
        items = items,
        onToggle = Planner::toggleTodo,
        onRename = Planner::renameTodo,
        onRemove = Planner::removeTodo,
    )
    if (items.any { it.done }) {
        ClockButton("완료한 항목 지우기", primary = false, modifier = Modifier.fillMaxWidth()) { Planner.clearDoneTodo() }
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
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .padding(start = 14.dp, end = 6.dp),
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
            Icon(Reicons.Add, contentDescription = "추가", tint = if (text.isNotBlank()) scheme.primary else scheme.outline)
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(item.done, onToggle)
        Spacer(Modifier.width(10.dp))
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
            IconButton(onClick = { onRename(text) }) { Icon(Reicons.Check, contentDescription = "저장") }
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
                Icon(Reicons.Close, contentDescription = "삭제", tint = scheme.outline, modifier = Modifier.size(18.dp))
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
        if (done) Icon(Reicons.Check, contentDescription = "완료", tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
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
        IconButton(onClick = { c.month = c.month.minusMonths(1) }) { Icon(Reicons.ChevronLeft, contentDescription = "이전 달") }
        Text(
            "${c.month.year}년 ${c.month.monthValue}월",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { c.month = c.month.plusMonths(1) }) { Icon(Reicons.ChevronRight, contentDescription = "다음 달") }
        ClockChip("오늘", selected = false) {
            c.month = today.withDayOfMonth(1)
            c.selected = today
        }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, name ->
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (week in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
        "${c.selected.monthValue}월 ${c.selected.dayOfMonth}일 " +
            c.selected.dayOfWeek.getDisplayName(DateTextStyle.FULL, Locale.KOREAN) +
            if (items.isNotEmpty()) " · ${items.count { it.done }}/${items.size}" else "",
        style = MaterialTheme.typography.titleMedium,
    )
    // Keyed by day, so a half-typed line stays with the day it was typed for.
    androidx.compose.runtime.key(key) {
        AddRow(placeholder = "이 날의 할 일 추가") { Planner.addToDay(key, it) }
    }
    if (items.isEmpty()) {
        Text("이 날에 적어 둔 것이 없습니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
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
            .clip(RoundedCornerShape(12.dp))
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
