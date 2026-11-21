package com.fcd.dashboard

/**
 * The status report as readable HTML: the Markdown of [Report] (headings, tables, lists, paragraphs, bold, code)
 * turned into HTML. Every text is escaped first; nothing from a log line ends up as HTML.
 */
object ReportHtml {
    /** The content only, for inside the Dashboard. */
    fun fragment(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> {
                    i++
                }

                line.startsWith("### ") -> {
                    out
                        .append("<h3>")
                        .append(inline(line.removePrefix("### ")))
                        .append("</h3>\n")
                        .also { i++ }
                }

                line.startsWith("## ") -> {
                    out
                        .append("<h2>")
                        .append(inline(line.removePrefix("## ")))
                        .append("</h2>\n")
                        .also { i++ }
                }

                line.startsWith("# ") -> {
                    out
                        .append("<h1>")
                        .append(inline(line.removePrefix("# ")))
                        .append("</h1>\n")
                        .also { i++ }
                }

                line.startsWith("|") -> {
                    val block = lines.drop(i).takeWhile { it.startsWith("|") }
                    out.append(table(block))
                    i += block.size
                }

                line.startsWith("- ") -> {
                    val block = lines.drop(i).takeWhile { it.startsWith("- ") }
                    out.append("<ul>\n")
                    block.forEach { out.append("<li>").append(inline(it.removePrefix("- "))).append("</li>\n") }
                    out.append("</ul>\n")
                    i += block.size
                }

                else -> {
                    val block =
                        lines.drop(i).takeWhile {
                            it.isNotBlank() && !it.startsWith("|") && !it.startsWith("#") &&
                                !it.startsWith("- ")
                        }
                    out.append("<p>").append(block.joinToString("<br>\n") { inline(it) }).append("</p>\n")
                    i += block.size
                }
            }
        }
        return out.toString()
    }

    /** A whole page, to keep and read without the Dashboard. */
    fun page(
        markdown: String,
        title: String,
    ): String =
        """
        |<!doctype html>
        |<html lang="nl">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>${escape(title)}</title>
        |<style>$STYLE</style>
        |</head>
        |<body>
        |<main class="report">
        |${fragment(markdown)}</main>
        |</body>
        |</html>
        |
        """.trimMargin()

    private fun table(rows: List<String>): String {
        fun cells(row: String) =
            row
                .trim()
                .removePrefix("|")
                .removeSuffix("|")
                .split("|")
                .map { it.trim() }
        val header = cells(rows.first())
        val body = rows.drop(1).filterNot { r -> cells(r).all { it.matches(Regex("-{3,}|:?-+:?")) } }
        val out = StringBuilder("<table>\n")
        if (header.any { it.isNotEmpty() }) {
            out.append("<thead><tr>")
            header.forEach { out.append("<th>").append(inline(it)).append("</th>") }
            out.append("</tr></thead>\n")
        }
        out.append("<tbody>\n")
        body.forEach { r ->
            out.append("<tr>")
            cells(r).forEach { out.append("<td>").append(inline(it)).append("</td>") }
            out.append("</tr>\n")
        }
        return out.append("</tbody>\n</table>\n").toString()
    }

    private fun inline(text: String): String =
        escape(text)
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")
            .replace(Regex("(^|\\s)_([^_]+)_(?=\\s|$|[.,;:])"), "$1<em>$2</em>")

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private val STYLE =
        """
        body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; color: #1f2328; background: #fff; margin: 0; }
        .report { max-width: 960px; margin: 0 auto; padding: 24px 16px 48px; line-height: 1.5; }
        h1 { font-size: 1.6rem; margin: 0 0 16px; }
        h2 { font-size: 1.25rem; margin: 28px 0 8px; border-bottom: 1px solid #d0d7de; padding-bottom: 4px; }
        h3 { font-size: 1.05rem; margin: 20px 0 6px; }
        table { border-collapse: collapse; margin: 8px 0 12px; width: 100%; font-size: 0.92rem; }
        th, td { border: 1px solid #d0d7de; padding: 6px 10px; text-align: left; vertical-align: top; }
        th { background: #f6f8fa; }
        code { background: #f6f8fa; padding: 0 4px; border-radius: 4px; }
        em { color: #59636e; }
        @media print { .report { padding: 0; } }
        """.trimIndent().replace("\n", " ")
}
