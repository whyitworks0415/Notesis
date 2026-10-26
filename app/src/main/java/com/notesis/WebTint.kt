package com.notesis

/**
 * What colours the top of a web page is, asked of the page itself, at
 * [PAGE_TOP_SAMPLES] points evenly across its first rows - for each, the
 * element there or the first ancestor of it that paints a background; failing
 * that the page's theme colour for the scheme it is in, then the body - and
 * then the colour at the middle of its bottom rows. Answers the top colours
 * joined by "|", a ";", and the bottom one; "" for any it could not tell.
 */
internal const val PAGE_TOP_COLOR_JS = """(function(){
  function solid(c){
    if(!c) return null;
    var m=c.match(/rgba?\(([^)]+)\)/);
    if(m){ var p=m[1].split(',').map(parseFloat); if(p.length>3 && p[3]<0.5) return null; return c; }
    return c;
  }
  function at(x,y){
    var el=document.elementFromPoint(x,y);
    while(el){ var c=solid(getComputedStyle(el).backgroundColor); if(c) return c; el=el.parentElement; }
    return null;
  }
  function fallback(){
    var metas=document.querySelectorAll('meta[name="theme-color"]');
    for(var i=0;i<metas.length;i++){
      var m=metas[i]; var q=m.getAttribute('media');
      if(m.content && (!q || window.matchMedia(q).matches)) return m.content;
    }
    if(document.body){ var b=solid(getComputedStyle(document.body).backgroundColor); if(b) return b; }
    return solid(getComputedStyle(document.documentElement).backgroundColor) || '';
  }
  try {
    var w=window.innerWidth, h=window.innerHeight, n=5, top=[];
    for(var i=0;i<n;i++){ top.push(at(w*(i+0.5)/n, 2) || fallback()); }
    return top.join('|') + ';' + (at(w/2, h-3) || fallback());
  } catch(e) { return ''; }
})()"""

/** How many points across the page's top are read for the bar's gradient. */
internal const val PAGE_TOP_SAMPLES = 5

/**
 * How often the web panel looks at the page again: often enough that the bar
 * follows a scroll or a change of theme as it happens, each change eased in.
 */
internal const val PAGE_TOP_COLOR_POLL_MS = 200L

/** The page's colours as [PAGE_TOP_COLOR_JS] tells them: across the top, left to right, and at the bottom. */
internal data class PageColors(val top: List<Int>, val bottom: Int?)

/**
 * [PAGE_TOP_COLOR_JS]'s answer, as handed back by evaluateJavascript, read
 * into colours. A point it could not tell takes its nearest neighbour's, so
 * the gradient has no holes; null when no point at the top could be told.
 */
internal fun parsePageColors(result: String?): PageColors? {
    if (result == null) return null
    val text = result.trim().removeSurrounding("\"")
    if (text.isEmpty() || text == "null") return null
    val halves = text.split(';')
    val read = halves[0].split('|').map { parseCssColor(it) }
    if (read.all { it == null }) return null
    val top = read.indices.map { i ->
        read[i] ?: read.indices.filter { read[it] != null }.minBy { kotlin.math.abs(it - i) }.let { read[it]!! }
    }
    return PageColors(top, halves.getOrNull(1)?.let { parseCssColor(it) })
}

/**
 * The colour in a value [android.webkit.WebView.evaluateJavascript] handed
 * back - a JSON string such as "\"rgb(32, 33, 36)\"" - as an opaque ARGB
 * int, or null when it is not a colour or is mostly see-through (the bar
 * then keeps its own). rgb(), rgba() and #rgb, #rrggbb, #rrggbbaa are read.
 */
internal fun parseCssColor(result: String?): Int? {
    if (result == null) return null
    val text = result.trim().removeSurrounding("\"").replace("\\\"", "\"").trim().lowercase()
    if (text.isEmpty() || text == "null") return null
    val rgb = Regex("""rgba?\(\s*([^)]*)\)""").find(text)
    if (rgb != null) {
        val parts = rgb.groupValues[1].split(',', ' ', '/').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val channels = parts.take(3).map { part ->
            val number = part.removeSuffix("%").toFloatOrNull() ?: return null
            if (part.endsWith("%")) number * 2.55f else number
        }
        if (parts.size >= 4) {
            val alphaText = parts[3]
            val alpha = alphaText.removeSuffix("%").toFloatOrNull() ?: return null
            val fraction = if (alphaText.endsWith("%")) alpha / 100f else alpha
            if (fraction < 0.5f) return null
        }
        return opaque(channels[0], channels[1], channels[2])
    }
    if (text.startsWith("#")) {
        val hex = text.drop(1)
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return when (hex.length) {
            3, 4 -> {
                if (hex.length == 4 && hex[3].digitToInt(16) < 8) return null
                opaque(hex[0].digitToInt(16) * 17f, hex[1].digitToInt(16) * 17f, hex[2].digitToInt(16) * 17f)
            }
            6, 8 -> {
                if (hex.length == 8 && hex.substring(6, 8).toInt(16) < 128) return null
                opaque(hex.substring(0, 2).toInt(16).toFloat(), hex.substring(2, 4).toInt(16).toFloat(),
                    hex.substring(4, 6).toInt(16).toFloat())
            }
            else -> null
        }
    }
    return NAMED[text]
}

private fun opaque(r: Float, g: Float, b: Float): Int {
    fun c(v: Float) = v.toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/** The few names a theme colour is written in often enough to be worth knowing. */
private val NAMED = mapOf(
    "white" to 0xFFFFFFFF.toInt(),
    "black" to 0xFF000000.toInt(),
)
