@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import kotlin.js.Promise

// The few browser calls the app needs (ADR-029). Files stay the browser's File/Blob objects: Kotlin gets their name and
// type, and the content only where it must (see app.benbow.client.BrowserMedia).

/** The origin this page came from: the Benbow server (or, in development, the dev server that forwards /api to one). */
internal fun pageOrigin(): String = js("window.location.origin")

/** Puts [text] on the clipboard. */
internal fun copyText(text: String): Unit = js("navigator.clipboard.writeText(text)")

/** Opens [html] in a new tab, where the browser's own Print (Ctrl+P) prints it or saves a PDF. */
internal fun openHtml(html: String): Unit = js(
    """{
        const w = window.open('', '_blank');
        if (w) { w.document.open(); w.document.write(html); w.document.close(); }
    }""",
)

/** Hands [bytes] (a Uint8Array) to the browser's downloads, named [name]. */
internal fun saveDownload(name: String, mime: String, bytes: JsAny): Unit = js(
    """{
        const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
        const a = document.createElement('a');
        a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    }""",
)

/** Hands a Blob to the browser's downloads, named [name]. */
internal fun saveBlob(name: String, blob: JsAny): Unit = js(
    """{
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 600000);
    }""",
)

/** The browser's file dialog; [onFiles] gets the chosen File objects (an array). */
internal fun pickFiles(accept: String, multiple: Boolean, onFiles: (JsAny) -> Unit): Unit = js(
    """{
        const input = document.createElement('input');
        input.type = 'file'; input.accept = accept; input.multiple = multiple;
        input.onchange = () => { if (input.files.length) onFiles(Array.from(input.files)); };
        input.click();
    }""",
)

internal fun arrayLength(array: JsAny): Int = js("array.length")

internal fun arrayItem(array: JsAny, index: Int): JsAny = js("array[index]")

internal inline fun forEachItem(array: JsAny, block: (JsAny) -> Unit) {
    for (i in 0 until arrayLength(array)) block(arrayItem(array, i))
}

internal fun fileName(file: JsAny): String = js("file.name || ''")

internal fun fileType(file: JsAny): String = js("file.type || ''")

internal fun fileModified(file: JsAny): Double = js("file.lastModified || 0")

/**
 * While composed, files dragged over the page or pasted (a screenshot, an image copied from a page) go to [onFiles];
 * [onHover] tells while a drag with files is over the page. Pasted text is left to the editor. Returns what
 * [stopFileListeners] needs.
 */
internal fun listenForFiles(onHover: (Boolean) -> Unit, onFiles: (JsAny) -> Unit): JsAny = js(
    """(() => {
        const hasFiles = e => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
        let depth = 0;
        const enter = e => { if (!hasFiles(e)) return; e.preventDefault(); if (depth++ === 0) onHover(true); };
        const over = e => { if (!hasFiles(e)) return; e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; };
        const leave = e => { if (!hasFiles(e)) return; if (--depth <= 0) { depth = 0; onHover(false); } };
        const drop = e => {
            if (!hasFiles(e)) return;
            e.preventDefault(); depth = 0; onHover(false);
            const files = Array.from(e.dataTransfer.files);
            if (files.length) onFiles(files);
        };
        const paste = e => {
            const data = e.clipboardData;
            if (!data || Array.from(data.types || []).includes('text/plain')) return;
            const files = Array.from(data.files || []);
            if (files.length) { e.preventDefault(); onFiles(files); }
        };
        const all = [['dragenter', enter], ['dragover', over], ['dragleave', leave], ['drop', drop], ['paste', paste]];
        for (const [name, f] of all) document.addEventListener(name, f, true);
        return all;
    })()""",
)

internal fun stopFileListeners(listeners: JsAny): Unit = js(
    "{ for (const [name, f] of listeners) document.removeEventListener(name, f, true); }",
)

/**
 * What the browser can tell about a picture, video or sound: size, length and, for pictures and videos, a JPEG of at
 * most 512 px for lists (a frame about a second in for videos). Anything it can't read (HEIC, say) gives zeros.
 */
internal fun probeMedia(file: JsAny, kind: String): Promise<JsAny> = js(
    """(async () => {
        const out = { width: 0, height: 0, durationMs: 0, preview: null };
        const jpeg = async (source, w, h) => {
            const scale = Math.min(1, 512 / Math.max(w, h));
            const canvas = new OffscreenCanvas(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            canvas.getContext('2d').drawImage(source, 0, 0, canvas.width, canvas.height);
            const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
            return new Uint8Array(await blob.arrayBuffer());
        };
        const event = (el, name, ms) => new Promise((ok, fail) => {
            el.addEventListener(name, ok, { once: true });
            el.addEventListener('error', fail, { once: true });
            setTimeout(() => fail(new Error(name + ' timed out')), ms);
        });
        try {
            if (kind === 'image') {
                const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
                out.width = bitmap.width; out.height = bitmap.height;
                out.preview = await jpeg(bitmap, bitmap.width, bitmap.height);
                bitmap.close();
            } else if (kind === 'video' || kind === 'audio') {
                const url = URL.createObjectURL(file);
                try {
                    const el = document.createElement(kind);
                    el.muted = true; el.preload = 'auto'; el.src = url;
                    await event(el, 'loadedmetadata', 15000);
                    if (isFinite(el.duration)) out.durationMs = Math.round(el.duration * 1000);
                    if (kind === 'video' && el.videoWidth) {
                        out.width = el.videoWidth; out.height = el.videoHeight;
                        el.currentTime = isFinite(el.duration) ? Math.min(1, el.duration / 3) : 0;
                        await event(el, 'seeked', 15000);
                        out.preview = await jpeg(el, el.videoWidth, el.videoHeight);
                    }
                } finally { URL.revokeObjectURL(url); }
            }
        } catch (e) { console.warn('Benbow: no details for', file.name, e); }
        return out;
    })()""",
)

internal fun probeWidth(details: JsAny): Int = js("details.width")

internal fun probeHeight(details: JsAny): Int = js("details.height")

internal fun probeDuration(details: JsAny): Double = js("details.durationMs")

internal fun probePreview(details: JsAny): JsAny? = js("details.preview")
