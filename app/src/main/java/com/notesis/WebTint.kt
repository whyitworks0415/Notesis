package com.notesis

/**
 * What colour the top of a web page is, asked of the page itself: the
 * element under the middle of its first rows, or the first ancestor of it
 * that paints a background; then the page's theme colour for the scheme it
 * is in; then the body. Answers a CSS colour string, or "" for nothing.
 */
internal const val PAGE_TOP_COLOR_JS = """(function(){
  function solid(c){
    if(!c) return null;
    var m=c.match(/rgba?\(([^)]+)\)/);
    if(m){ var p=m[1].split(',').map(parseFloat); if(p.length>3 && p[3]<0.5) return null; return c; }
    return c;
  }
  try {
    var el=document.elementFromPoint(window.innerWidth/2, 2);
    while(el){ var c=solid(getComputedStyle(el).backgroundColor); if(c) return c; el=el.parentElement; }
    var metas=document.querySelectorAll('meta[name="theme-color"]');
    for(var i=0;i<metas.length;i++){
      var m=metas[i]; var q=m.getAttribute('media');
      if(m.content && (!q || window.matchMedia(q).matches)) return m.content;
    }
    if(document.body){ var b=solid(getComputedStyle(document.body).backgroundColor); if(b) return b; }
    return solid(getComputedStyle(document.documentElement).backgroundColor) || '';
  } catch(e) { return ''; }
})()"""

/** How often the web panel looks at the page's top again: it scrolls, and chats change theme. */
internal const val PAGE_TOP_COLOR_POLL_MS = 1500L

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
