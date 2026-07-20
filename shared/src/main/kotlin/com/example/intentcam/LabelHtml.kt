package com.example.intentcam

/**
 * Markdown-subset → HTML renderer for the `view_label` action's
 * rendered-label page.
 *
 * Why hand-rolled: the app deliberately avoids a third-party
 * markdown dependency (build environment has had artifact-download
 * failures; APK size budget).  The LLM's `label_markdown` contract
 * only promises a small subset — headings, `-`/`1.` lists, GFM
 * tables, `**bold**`, `---` rules, paragraphs — so a deterministic
 * ~150-line converter covers the full contract surface.
 *
 * Pure Kotlin (no `android.*`) so it lives in `shared/` next to the
 * contract that produces the markdown, and stays unit-testable on
 * the JVM.  The WebView wrapper + bitmap capture are app-side.
 *
 * Security: every text node is HTML-escaped *before* inline markup
 * is applied, so LLM output can never inject tags/script into the
 * WebView (which also runs with JavaScript disabled).
 */
object LabelHtml {

    /** Render the full label page document: built-in CSS template +
     *  [title] header + [markdown] body.  The page is width-fluid;
     *  the hosting WebView is measured to content height. */
    fun labelPageHtml(title: String, markdown: String): String =
        pageHtml(title, markdown, imageDataUri = null)

    /** Render the view_ad page: like [labelPageHtml] but with the
     *  corrected ad image ([imageDataUri], a `data:` URI) shown at
     *  the top of the body — the 图文复现 layout (image on top,
     *  transcription below).  Null image → text-only page. */
    fun adPageHtml(title: String, markdown: String, imageDataUri: String?): String =
        pageHtml(title, markdown, imageDataUri)

    private fun pageHtml(title: String, markdown: String, imageDataUri: String?): String {
        val body = markdownToHtml(markdown)
        val safeTitle = escape(title)
        val imgBlock = imageDataUri?.let {
            """  <img class="ad-image" src="$it" alt="广告图片"/>""" + "\n"
        } ?: ""
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<!-- device-width viewport: layout width = screen width in CSS px,
     so 1 CSS px = 1 dp and font sizes below are dp-equivalents.
     (The first version omitted this: without a viewport meta the
     WebView lays out at PHYSICAL pixel width — 15px type shrank to
     ≈5dp on a 3× display, the "页面很小" user report 2026-07-19.) -->
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>
$CSS
</style>
</head>
<body>
<div class="label-page">
  <div class="label-header">$safeTitle</div>
$imgBlock  <div class="label-body">
$body
  </div>
</div>
</body>
</html>"""
    }

    /** Convert the contract markdown subset to an HTML fragment.
     *  Block-level: `#`..`######` headings, `-`/`*`/`+` bullet lists,
     *  `1.` ordered lists, GFM pipe tables (delimiter row required),
     *  `---`/`***`/`___` rules, paragraphs (single newlines become
     *  <br/> — labels are line-oriented, so line breaks are
     *  meaningful).  Inline: `**bold**`, `` `code` ``. */
    fun markdownToHtml(md: String): String {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> { i++; continue }
                isRule(trimmed) -> { out.append("<hr/>\n"); i++ }
                headingLevel(trimmed) > 0 -> {
                    val level = headingLevel(trimmed)
                    val text = trimmed.substring(level).trim()
                    out.append("<h$level>").append(inline(text)).append("</h$level>\n")
                    i++
                }
                isTableStart(lines, i) -> {
                    val end = consumeTable(lines, i, out)
                    i = end
                }
                isBullet(trimmed) -> {
                    out.append("<ul>\n")
                    while (i < lines.size && isBullet(lines[i].trim())) {
                        out.append("<li>")
                            .append(inline(stripBullet(lines[i].trim())))
                            .append("</li>\n")
                        i++
                    }
                    out.append("</ul>\n")
                }
                isOrdered(trimmed) -> {
                    out.append("<ol>\n")
                    while (i < lines.size && isOrdered(lines[i].trim())) {
                        out.append("<li>")
                            .append(inline(stripOrdered(lines[i].trim())))
                            .append("</li>\n")
                        i++
                    }
                    out.append("</ol>\n")
                }
                else -> {
                    // Paragraph: consecutive plain lines join with <br/>.
                    val para = StringBuilder("<p>")
                    var first = true
                    while (i < lines.size && isPlainParagraphLine(lines, i)) {
                        if (!first) para.append("<br/>\n")
                        para.append(inline(lines[i].trim()))
                        first = false
                        i++
                    }
                    para.append("</p>\n")
                    out.append(para)
                }
            }
        }
        return out.toString()
    }

    // ── block-level predicates ─────────────────────────────────────

    private fun headingLevel(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == '#') n++
        return if (n in 1..6 && line.length > n && line[n] == ' ') n else 0
    }

    private fun isRule(line: String): Boolean {
        if (line.length < 3) return false
        val chars = line.filter { it != ' ' }
        return chars.length >= 3 &&
            (chars.all { it == '-' } || chars.all { it == '*' } || chars.all { it == '_' })
    }

    private fun isBullet(line: String): Boolean =
        line.length >= 2 && (line[0] == '-' || line[0] == '*' || line[0] == '+') && line[1] == ' '

    private fun stripBullet(line: String): String = line.substring(2).trim()

    private val orderedRe = Regex("""^\d+[.)]\s+""")

    private fun isOrdered(line: String): Boolean = orderedRe.containsMatchIn(line)

    private fun stripOrdered(line: String): String = line.replace(orderedRe, "").trim()

    /** A GFM table needs a header row and a delimiter row of `-`/`:`.
     *  Without the delimiter we treat the pipes as plain paragraph
     *  text (the LLM occasionally emits ASCII-art that isn't a
     *  table). */
    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val header = lines[i].trim()
        val delim = lines[i + 1].trim()
        return looksLikeTableRow(header) && isTableDelimiter(delim)
    }

    private fun looksLikeTableRow(line: String): Boolean =
        line.startsWith("|") && line.endsWith("|") && line.length > 2

    private fun isTableDelimiter(line: String): Boolean {
        if (!looksLikeTableRow(line)) return false
        return splitRow(line).all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' }
        }
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|')

    private fun consumeTable(lines: List<String>, start: Int, out: StringBuilder): Int {
        var i = start
        val header = splitRow(lines[i]).map { it.trim() }
        i += 2 // skip header + delimiter
        out.append("<table>\n<thead><tr>")
        header.forEach { out.append("<th>").append(inline(it)).append("</th>") }
        out.append("</tr></thead>\n<tbody>\n")
        while (i < lines.size && looksLikeTableRow(lines[i].trim())) {
            val cells = splitRow(lines[i]).map { it.trim() }
            out.append("<tr>")
            // Pad/truncate ragged rows to the header width.
            for (c in 0 until header.size) {
                out.append("<td>").append(inline(cells.getOrElse(c) { "" })).append("</td>")
            }
            out.append("</tr>\n")
            i++
        }
        out.append("</tbody>\n</table>\n")
        return i
    }

    /** True while the current line continues a plain paragraph (not
     *  blank, not the start of any other block).  A pipe row that is
     *  NOT a valid table start (no delimiter row) is paragraph TEXT
     *  here — the table branch above only claims delimiter-backed
     *  tables.  (2026-07-19 smoke-test catch: an earlier version also
     *  excluded bare `looksLikeTableRow` lines from paragraphs, so a
     *  delimiter-less pipe row matched NO branch that makes progress
     *  and spun the outer loop forever.) */
    private fun isPlainParagraphLine(lines: List<String>, i: Int): Boolean {
        val t = lines[i].trim()
        if (t.isEmpty()) return false
        return headingLevel(t) == 0 && !isRule(t) && !isBullet(t) && !isOrdered(t) &&
            !isTableStart(lines, i)
    }

    // ── inline markup ──────────────────────────────────────────────

    private val boldRe = Regex("""\*\*(.+?)\*\*""")
    private val codeRe = Regex("""`([^`]+)`""")

    /** Escape first, then re-introduce our own tags.  The contract
     *  subset has no raw-HTML passthrough on purpose. */
    private fun inline(text: String): String {
        var s = escape(text)
        s = boldRe.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = codeRe.replace(s) { "<code>${it.groupValues[1]}</code>" }
        return s
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    /** Full-screen label page template.  Clean white page (the app
     *  chrome above/below it is dark); CJK font stack; full-width
     *  bordered tables.  Font sizes in px which, with the
     *  device-width viewport, are dp-equivalents — so the page reads
     *  at the same physical size on any screen. */
    private const val CSS = """
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { background: #ffffff; }
body {
  font-family: system-ui, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
  color: #1a1a1a;
  font-size: 16px;
  line-height: 1.6;
}
.label-page {
  background: #ffffff;
  /* no min-height:100vh — vh resolves against the WebView's own
     height, which is unstable in the off-screen capture path (view
     laid out at 1px before load); the Compose side already paints
     the white background. */
  padding: 18px 20px 24px;
}
.label-header {
  font-size: 13px;
  color: #6b7280;
  letter-spacing: 0.05em;
  padding-bottom: 10px;
  margin-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}
.ad-image {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  margin-bottom: 12px;
}
.label-body h1 { font-size: 22px; text-align: center; margin: 6px 0 12px; }
.label-body h2 { font-size: 18px; margin: 12px 0 6px; }
.label-body h3 { font-size: 16px; margin: 10px 0 4px; color: #374151; }
.label-body h4, .label-body h5, .label-body h6 { font-size: 15px; margin: 8px 0 4px; color: #4b5563; }
.label-body p { margin: 7px 0; }
.label-body ul, .label-body ol { margin: 7px 0 7px 1.3em; }
.label-body li { margin: 3px 0; }
.label-body hr { border: none; border-top: 1px dashed #cbd5e1; margin: 12px 0; }
.label-body table {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
  font-size: 15px;
}
.label-body th, .label-body td {
  border: 1px solid #d1d5db;
  padding: 6px 9px;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
}
.label-body th { background: #f3f4f6; font-weight: 600; }
.label-body code {
  font-family: ui-monospace, monospace;
  background: #f3f4f6;
  border-radius: 4px;
  padding: 0 4px;
  font-size: 14px;
}
.label-body strong { font-weight: 600; }
"""
}
