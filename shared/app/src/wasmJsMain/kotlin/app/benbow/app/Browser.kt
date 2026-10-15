@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

// The few browser calls the app needs (ADR-029). Bytes cross into JavaScript as base64: simple, and fast enough for
// the files a journal holds.

/** Puts [text] on the clipboard. */
internal fun copyText(text: String): Unit = js("navigator.clipboard.writeText(text)")

/** Opens [html] in a new tab, where the browser's own Print (Ctrl+P) prints it or saves a PDF. */
internal fun openHtml(html: String): Unit = js(
    """{
        const w = window.open('', '_blank');
        if (w) { w.document.open(); w.document.write(html); w.document.close(); }
    }""",
)

/** Hands a file to the browser's downloads, named [name]. */
internal fun downloadBase64(name: String, mime: String, base64: String): Unit = js(
    """{
        const raw = atob(base64);
        const bytes = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
        const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
        const a = document.createElement('a');
        a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    }""",
)

/** The browser's file dialog; each chosen file comes to [onFile] as name, type, base64 content and its last change. */
internal fun pickFiles(accept: String, multiple: Boolean, onFile: (String, String, String, Double) -> Unit): Unit = js(
    """{
        const input = document.createElement('input');
        input.type = 'file'; input.accept = accept; input.multiple = multiple;
        input.onchange = async () => {
            for (const f of input.files) {
                const buf = new Uint8Array(await f.arrayBuffer());
                let s = '';
                for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
                onFile(f.name, f.type || 'application/octet-stream', btoa(s), f.lastModified);
            }
        };
        input.click();
    }""",
)
