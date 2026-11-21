package com.fcd.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportHtmlTest {
    private val markdown =
        """
        # Statusrapport showcase demo

        | | |
        |---|---|
        | Experiment | demo-1 |

        ## Key result

        Elke bestelling komt aan: **50.0%** (1 van 2).

        | Versie | Bestellingen | Volledig aangekomen | Key result |
        |---|---|---|---|
        | 1.0.0 | 1 | 0 | 0.0% |
        | 1.0.1 | 1 | 1 | 100.0% |

        Goedgekeurde afwijkingen:
        - EXP-001 <script>alert(1)</script>

        _Gemaakt door het Dashboard._
        """.trimIndent()

    @Test
    fun `headings, tables with and without a header, lists, bold and the closing line`() {
        val html = ReportHtml.fragment(markdown)
        assertTrue(html.contains("<h1>Statusrapport showcase demo</h1>"))
        assertTrue(html.contains("<table>\n<tbody>\n<tr><td>Experiment</td><td>demo-1</td></tr>"), html)
        assertTrue(
            html.contains("<thead><tr><th>Versie</th><th>Bestellingen</th><th>Volledig aangekomen</th><th>Key result</th></tr></thead>"),
        )
        assertTrue(html.contains("<tr><td>1.0.1</td><td>1</td><td>1</td><td>100.0%</td></tr>"))
        assertTrue(html.contains("<strong>50.0%</strong>"))
        assertTrue(html.contains("<p><em>Gemaakt door het Dashboard.</em></p>"), html)
    }

    @Test
    fun `text from log lines is never HTML`() {
        val html = ReportHtml.fragment(markdown)
        assertTrue(html.contains("<li>EXP-001 &lt;script&gt;alert(1)&lt;/script&gt;</li>"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `a whole page to keep, with its title`() {
        val page = ReportHtml.page(markdown, "Statusrapport demo – demo-1")
        assertTrue(page.startsWith("<!doctype html>"))
        assertEquals(1, Regex("<title>Statusrapport demo – demo-1</title>").findAll(page).count())
        assertTrue(page.contains("<main class=\"report\">"))
    }
}
