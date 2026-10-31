package com.notesis

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.json.JSONArray
import org.json.JSONObject

/** A site the person added to Web & AI, by the name they gave it. */
internal data class WebSite(val name: String, val url: String)

/**
 * The sites added to Web & AI beside the ones the app comes with: offered in
 * the settings, in the bar's AI menu and on the browser panel's chips, in the
 * order they were added. Kept with the other settings, so a settings file
 * carries them to another tablet.
 */
internal object WebSites {
    private var prefs: SharedPreferences? = null

    val added = mutableStateListOf<WebSite>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
        prefs = p
        added.clear()
        added += decode(p.getString(KEY, null))
    }

    /** Adds [url] as [name]; false when the address is not one a browser can open. */
    fun add(name: String, url: String): Boolean {
        val address = normalized(url) ?: return false
        val label = name.trim().ifEmpty { hostOf(address) }.take(40)
        // The same address again only takes the new name.
        val at = added.indexOfFirst { it.url == address }
        if (at >= 0) added[at] = WebSite(label, address) else added += WebSite(label, address)
        save()
        return true
    }

    fun remove(site: WebSite) {
        added.remove(site)
        save()
    }

    fun move(site: WebSite, delta: Int) {
        val from = added.indexOf(site)
        val to = from + delta
        if (from < 0 || to !in added.indices) return
        added.removeAt(from)
        added.add(to, site)
        save()
    }

    private fun save() {
        prefs?.edit()?.putString(KEY, encode(added))?.apply()
    }

    /** "example.com/x" is taken as https; anything that is not http or https with a host is refused. */
    fun normalized(url: String): String? {
        val text = url.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in text) text else "https://$text"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host.orEmpty()
        if (host.isEmpty() || "." !in host && host != "localhost") return null
        return withScheme
    }

    fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("").ifEmpty { url }

    internal fun encode(sites: List<WebSite>): String = JSONArray().apply {
        sites.forEach { put(JSONObject().put("name", it.name).put("url", it.url)) }
    }.toString()

    internal fun decode(json: String?): List<WebSite> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WebSite(item.optString("name").ifBlank { hostOf(url) }, url)
            }
        }.getOrDefault(emptyList())
    }

    private const val KEY = "webSites"
}

/** The sites added to Web & AI, each with a way to open, reorder or drop it, and one to add another. */
@Composable
internal fun AddedSitesEditor(onOpen: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    SettingsSectionHeader("Your sites")
    if (WebSites.added.isEmpty()) {
        SettingsNote("Add any address - a dictionary, a class page, another AI. It shows here, in the bar's AI menu and on the browser panel.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        WebSites.added.toList().forEachIndexed { index, site ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(site.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onOpen(site.url) }) { Text("Open") }
                TextButton(enabled = index > 0, onClick = { WebSites.move(site, -1) }) { Text("Up") }
                IconButton(onClick = { WebSites.remove(site) }) {
                    Icon(Reicons.Close, contentDescription = "Remove ${site.name}")
                }
            }
        }
    }
    TextButton(onClick = { adding = true }) { Text("+ Add a site") }
    if (adding) AddSiteDialog(onDismiss = { adding = false })
}

/** Asks for an address and a name for it; [initialUrl] fills the address, as from the page open now. */
@Composable
internal fun AddSiteDialog(onDismiss: () -> Unit, initialUrl: String = "", initialName: String = "") {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    val valid = WebSites.normalized(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a site") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                    label = { Text("Address") }, placeholder = { Text("example.com") },
                    isError = url.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, placeholder = { Text(if (valid) WebSites.hostOf(WebSites.normalized(url)!!) else "Name") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (WebSites.add(name, url)) onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
