package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryParserTest {

    @Test
    fun testHistoryParser() {
        val mockHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <div class="rightpanel">
                <table>
                    <tbody>
                        <tr>
                            <td>2023-10-12 15:30:00 UTC</td>
                            <td>Latitude, Longitude: 48.26275, 13.296288</td>
                        </tr>
                        <tr>
                            <td>2023-09-28 12:00:00 UTC</td>
                            <td>No location available</td>
                        </tr>
                    </tbody>
                </table>
            </div>
            <script>
                downloadMemories('https://aws.s3.amazonaws.com/media1?mid=123-abc', this, true);
                downloadMemories('https://aws.s3.amazonaws.com/media2?mid=456-def', this, false);
            </script>
            </body>
            </html>
        """.trimIndent()

        val items = HistoryParser.parse(mockHtml)

        assertEquals(2, items.size)
        
        val item1 = items[0]
        assertEquals("123-abc", item1.id)
        assertEquals("https://aws.s3.amazonaws.com/media1?mid=123-abc", item1.url)
        assertTrue(item1.isGet)
        assertEquals("2023-10-12 15:30:00 UTC", item1.dateStr)
        assertEquals(48.26275, item1.latitude)
        assertEquals(13.296288, item1.longitude)

        val item2 = items[1]
        assertEquals("456-def", item2.id)
        assertEquals("https://aws.s3.amazonaws.com/media2?mid=456-def", item2.url)
        assertTrue(!item2.isGet)
        assertEquals("2023-09-28 12:00:00 UTC", item2.dateStr)
        assertEquals(null, item2.latitude)
        assertEquals(null, item2.longitude)
    }

    @Test
    fun testEmptyHtml() {
        val items = HistoryParser.parse("")
        assertTrue(items.isEmpty())
    }

    @Test
    fun testCorruptHtml() {
        val corruptHtml = "<html><body><h1>Random Content</h1></body></html>"
        val items = HistoryParser.parse(corruptHtml)
        assertTrue(items.isEmpty())
    }

    @Test
    fun testHtmlWithNoTableOnlyScripts() {
        val html = """
            <script>
                downloadMemories('https://aws.s3.amazonaws.com/media1?mid=123-abc', this, true);
            </script>
        """.trimIndent()
        val items = HistoryParser.parse(html)
        assertEquals(1, items.size)
        assertEquals("123-abc", items[0].id)
        assertEquals(null, items[0].dateStr)
        assertEquals(null, items[0].latitude)
    }
}
