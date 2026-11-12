package com.notesis

import android.graphics.Bitmap
import android.util.Base64
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Puts a picture straight into the chat open in the web panel, as if it had
 * been pasted there, so a capture reaches the AI without the chat's own
 * "+ › Files › pick" round trip.
 *
 * The page is handed the picture as a File and offered it the ways chat
 * sites take one: pasted into the message box (ChatGPT, Claude and Gemini
 * all read a pasted image), else set on the page's own file input, else
 * dropped on the page. A paste or drop counts only if the page took it -
 * cancelled the event, as a site that keeps the file does.
 */
internal object AiAttach {
    /** What came back: where the picture went, or [NONE] when the page had nowhere for it yet. */
    const val PASTED = "paste"
    const val INPUT = "input"
    const val DROPPED = "drop"
    const val NONE = "none"

    fun taken(result: String): Boolean = result == PASTED || result == INPUT || result == DROPPED

    /** The capture as the PNG the chat is handed. Off the main thread. */
    fun encode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Offers the picture in [base64] to the page in [web]; main thread. */
    suspend fun offer(web: WebView, base64: String, name: String = "notesis-capture.png"): String =
        suspendCancellableCoroutine { done ->
            runCatching {
                web.evaluateJavascript(script(base64, "image/png", name)) { result ->
                    if (done.isActive) done.resume(result?.trim('"') ?: NONE)
                }
            }.onFailure { if (done.isActive) done.resume(NONE) }
        }

    /**
     * Offers several files at once - a PDF of pages and the text read off
     * them - the same three ways as a picture; main thread. Each is
     * (base64, mime type, name); a name is made safe for the page's script.
     */
    suspend fun offerFiles(web: WebView, files: List<Triple<String, String, String>>): String =
        suspendCancellableCoroutine { done ->
            runCatching {
                web.evaluateJavascript(filesScript(files)) { result ->
                    if (done.isActive) done.resume(result?.trim('"') ?: NONE)
                }
            }.onFailure { if (done.isActive) done.resume(NONE) }
        }

    private fun filesScript(files: List<Triple<String, String, String>>): String {
        val list = files.joinToString(",") { (b64, mime, name) ->
            "['$b64','${mime.filter { it.isLetterOrDigit() || it in "/.+-" }}','${name.filter { it.isLetterOrDigit() || it in " .-_" }}']"
        }
        return """
        (function(list) {
          try {
            var files = list.map(function(f) {
              var bin = atob(f[0]), bytes = new Uint8Array(bin.length);
              for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
              return new File([bytes], f[2], { type: f[1] });
            });
            function transfer() { var dt = new DataTransfer(); files.forEach(function(f) { dt.items.add(f); }); return dt; }
            function visible(el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
            var boxes = Array.prototype.slice.call(document.querySelectorAll(
              '[contenteditable="true"], [contenteditable=""], textarea, [role="textbox"]')).filter(visible);
            var active = document.activeElement;
            var box = (active && active !== document.body && boxes.indexOf(active) >= 0) ? active : boxes[boxes.length - 1];
            if (box) {
              box.focus();
              var paste = new ClipboardEvent('paste', { clipboardData: transfer(), bubbles: true, cancelable: true });
              if (!box.dispatchEvent(paste)) return '$PASTED';
            }
            var inputs = Array.prototype.slice.call(document.querySelectorAll('input[type="file"]'));
            var input = inputs.filter(function(i) { return !i.accept || /\*|pdf|text/.test(i.accept); })[0] || inputs[0];
            if (input) {
              input.files = transfer().files;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              return '$INPUT';
            }
            var target = box || document.querySelector('main') || document.body;
            var drop = new DragEvent('drop', { dataTransfer: transfer(), bubbles: true, cancelable: true });
            if (!target.dispatchEvent(drop)) return '$DROPPED';
            return '$NONE';
          } catch (e) { return 'error:' + e; }
        })([$list]);
        """.trimIndent()
    }

    private fun script(base64: String, mime: String, name: String): String = """
        (function(b64, mime, name) {
          try {
            var bin = atob(b64), bytes = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
            var file = new File([bytes], name, { type: mime });
            function transfer() { var dt = new DataTransfer(); dt.items.add(file); return dt; }
            function visible(el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
            var boxes = Array.prototype.slice.call(document.querySelectorAll(
              '[contenteditable="true"], [contenteditable=""], textarea, [role="textbox"]')).filter(visible);
            var active = document.activeElement;
            var box = (active && active !== document.body && boxes.indexOf(active) >= 0) ? active : boxes[boxes.length - 1];
            if (box) {
              box.focus();
              var paste = new ClipboardEvent('paste', { clipboardData: transfer(), bubbles: true, cancelable: true });
              if (!box.dispatchEvent(paste)) return '$PASTED';
            }
            var inputs = Array.prototype.slice.call(document.querySelectorAll('input[type="file"]'));
            var input = inputs.filter(function(i) { return !i.accept || /image|\*/.test(i.accept); })[0] || inputs[0];
            if (input) {
              input.files = transfer().files;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              return '$INPUT';
            }
            var target = box || document.querySelector('main') || document.body;
            var drop = new DragEvent('drop', { dataTransfer: transfer(), bubbles: true, cancelable: true });
            if (!target.dispatchEvent(drop)) return '$DROPPED';
            return '$NONE';
          } catch (e) { return 'error:' + e; }
        })('$base64', '$mime', '$name');
    """.trimIndent()
}
