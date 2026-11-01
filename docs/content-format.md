# Content format: Benbow Document v1 (ADR-015)

Canonical storage for entry bodies (and template bodies). It is based on the **Portable Text** spec (MIT, github.com/portabletext/portabletext) plus the documented extensions below.
Markdown, plain text, HTML and PDF are **exports** (lossy except HTML). This file is the contract: change it only together with a format-version bump and a reader for the old version.

## Envelope
```json
{"v":1,"blocks":[ ... ]}
```
- `v` is the format version. A reader that sees `v` greater than it supports, or unknown envelope fields, opens the document **read-only** and never re-saves it.
- `blocks` is a flat array (PT style). Nesting is expressed by `level` and by a shared container `_key` (callouts).

## Text block (`_type: "block"`)
| Field | Values | Notes |
|---|---|---|
| `_key` | 8-char base62, unique in the document | **Stable identity.** When a paragraph splits, the first half keeps the key. When two merge, the first key survives. Pasted blocks get new keys |
| `style` | `normal` · `h1` · `h2` · `h3` · `blockquote` | Semantic type. Visuals come from the theme |
| `listItem` | `bullet` · `number` · `check` | optional |
| `level` | 1..6 | list nesting (PT convention, 1-based). Only with `listItem` |
| `checked` | bool | only with `listItem: "check"` |
| `align` | `start` · `center` · `end` | optional, default `start` |
| `callout` | `{"_key": "...", "tone": "<token>", "icon": "<emoji>"}` | optional. Consecutive blocks with the same callout `_key` render as one box. Orthogonal to `style` and `listItem` |
| `children` | spans | at least one. An empty block holds one empty span |
| `markDefs` | annotation objects | only defs that are referenced |

### Spans
`{"_type":"span","text":"...","marks":["strong","m1"]}`. A soft line break inside a block is **U+2028** (LINE SEPARATOR). `\n` never appears in span text, because the editor model treats every `\n` as a block boundary; the decoder maps a stray `\n` to U+2028. Otherwise text is stored exactly as typed: no Unicode normalization, no trimming.

### Decorators (string marks)
`strong`, `em`, `underline`, `strike-through`, `code`, `spoiler`. Names follow PT conventions where they exist.

### Annotations (`markDefs`, referenced by `_key` from `marks`)
| `_type` | Fields | |
|---|---|---|
| `link` | `href` | external URL |
| `entryLink` | `entryId`, `blockKey?` | link to another entry or a specific paragraph |
| `color` | `token` | text color from the palette |
| `highlight` | `token` | background highlight from the palette |
| `size` | `step`: `small` · `large` · `xlarge` | relative text size. Headings are styles, not sizes |

## Other block types
| `_type` | Fields | Editor behaviour |
|---|---|---|
| `code` | `_key`, `language?`, `code` (string, may contain `\n`), `callout?` | monospace region with background. Plain text only (no marks). The editor merges directly adjacent code blocks with the same callout |
| `divider` | `_key`, `variant?` (`line` · `dots` · `wave`) | atomic placeholder line |
| `attachment` | `_key`, `ref` (attachment id), `width?` (0..1), `caption?` | inline media (7.2): a picture, video, sound or drawing placed in the text; the file stays an attachment of the entry (gallery). Markdown links it in place, HTML shows it in place |
| `attachmentRow` | `_key`, `items` (2 or 3 `{"ref": <attachment id>}`, each a different attachment) | a row of attachments side by side (ADR-061): one height, each as wide as its own shape asks; no frames in it. Atomic; while writing a tap chooses one of its pictures (bubble: open, «Вложение», out of the row, the row apart, out of the text). Markdown writes its pictures on one line, HTML a flex row. Older readers keep it verbatim as an unknown block (read-only), so `v` stays 1; a row with one item, more than three, the same one twice or a field this app doesn't know is kept so too |
| `linkCard` | `_key`, `href`, `title?`, `description?`, `site?`, `image?` (SHA-256 of a JPEG blob), `view?` (`large`; default a row, `row` is dropped), `fetched?` (`YYYY-MM-DD`, the day the page was read) | a link as a card (7.15, ADR-038): what the page said when the link went in, kept for good. Atomic; a tap while writing selects it (bubble: open, look, down into the text, edit, remove). Older readers keep it verbatim as an unknown block, so `v` stays 1 |
| any unknown | preserved verbatim | atomic read-only chip. Never dropped or altered |

**Unknown-content rule:** a block with an unknown `_type`, an unknown field, an unknown mark/markDef or an invalid value is kept **verbatim as an unknown block** (read-only chip). The rest of the document stays editable. Structurally broken JSON (no `_key`/`_type`, not an object) fails decoding, and the revision history is the fallback.
Later atomic embeds (tables, toggles) are new `_type`s that follow the same rules.

## Theme tokens
Colors, highlights and callout tones are **token names**, never raw hex. The design system maps each token to light, dark and per-note-background values: in the app the theme's (ADR-057: the token's hue turned a little toward the palette, at tones that read 4.5:1 on every ground), on paper (printed pages, a drawing's SVG) the default palette's light ones. Initial palette (final names set in the design pass): `gray, brown, orange, yellow, green, teal, blue, purple, pink, red`. An unknown token renders as the default color, and its data is kept.

## Canonical serialization (hashing, diffs, merges)
- UTF-8 JSON, fixed key order per object type (order as in the tables above, `_key` and `_type` first), no insignificant whitespace, **exactly one block per line**:
  ```
  {"v":1,"blocks":[
  {"_key":"k3VbQ1aZ","_type":"block","style":"h1","children":[{"_type":"span","text":"Утро","marks":[]}],"markDefs":[]},
  {"_key":"p0Lm77Qx","_type":"divider"}
  ]}
  ```
  Line-based diff3 therefore merges at the paragraph level for free (sync.md), and Markdown/JSON mirror diffs stay readable.
- Normalization (idempotent): merge adjacent spans with identical marks, drop empty spans except in an empty block, sort marks (decorators alphabetically, then annotation keys in first-use order), keep only referenced markDefs, renumber markDef keys per block as `m1, m2, ...` in first-use order. Remove fields equal to their default (`align:"start"`).
- `body_sha256` = SHA-256 of the canonical bytes.
- Tests (property-based): `decode(encode(doc)) == doc`, `canon(canon(x)) == canon(x)`, every `_key` is unique, unknown types and fields round-trip byte-identically.

## Derived forms
- **Plain text** (search index, previews, notifications): block texts joined by `\n`. List and check prefixes are omitted. Code included. Spoiler text is excluded from titles, previews and notifications but indexed for search (titles too since 7.8: a list card showed the hidden words; the client makes its cached titles and previews again once, `prepareSummaries`).
- **Markdown export**, two flavours:
  - *rich* (default, Obsidian-friendly): `**`, `*`, `<u>`, `~~`, `` ` ``, spoiler → `<span class="spoiler">`, color/size → `<span style>`, highlight → `==x==` (default token) or `<mark style>`, align → HTML wrapper, callout → `> [!note] <icon>`, check → `- [ ]` / `- [x]`, entryLink → relative link to the exported file.
  - *clean*: drop all styling that CommonMark+GFM can't express.
  Entry metadata → YAML front matter.
- **Link cards** (ADR-038): plain text is the title and the address (search finds the site, a copy keeps the link); list titles and previews take the title or the short address (`habr.com/ru/…`); Markdown `[Title](address)` with `description · site` on the next line (an address with spaces or brackets in `<…>`); HTML an `a.link-card` with the picture when the exporter knows where it is.
- **HTML export:** lossless, CSS generated from the palette (light theme by default). As built (6.3): `HtmlExporter` in shared/core; every style, list, callout and mark has a tag or class (spoilers keep their text under `.spoiler`), colors come from the app palette as CSS values; `HtmlExporter.page` wraps it in a print-friendly page. Used for "PDF и печать".
- **Import** (Markdown/HTML → Document): supported constructs map back. Anything else becomes a preserved unknown block (`_type: "raw"`, `format`, `source`).

## Drawings (7.5, ADR-026)
An attachment of kind `drawing`, MIME `image/svg+xml`, name `drawing.svg`. The SVG is viewable as is (`viewBox="0 0 <width> <height>"`, one `<path>` per stroke: pens as filled outlines, markers as round-capped lines at `stroke-opacity` 0.35, colors from the light palette). Its `<metadata>` holds `<benbow-ink xmlns="https://benbow.app/ns/ink" version="1"><![CDATA[json]]></benbow-ink>` with `{"width":1000,"height":750,"strokes":[{"tool":"Pen"|"Marker","color":"<token>","size":8.0,"points":[x,y,t,...]}]}`: logical units (1000 across), t in ms from the stroke's start, x/y rounded to tenths. `color` is a palette token or `ink` (the theme's text color). Unknown fields are ignored; a file without the metadata is just a picture.
