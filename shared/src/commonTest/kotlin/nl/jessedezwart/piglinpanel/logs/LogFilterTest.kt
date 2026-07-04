package nl.jessedezwart.piglinpanel.logs

import kotlin.test.Test
import kotlin.test.assertEquals

class LogFilterTest {
    @Test
    fun keepsMatchingLinesCaseInsensitively() {
        val content = "INFO started\nWARN Something failed\ninfo done"
        assertEquals("INFO started\ninfo done\n", LogFilter.apply(content, "info"))
    }

    @Test
    fun blankFilterReturnsContentUnchanged() {
        assertEquals("a\nb", LogFilter.apply("a\nb", ""))
        assertEquals("a\nb", LogFilter.apply("a\nb", null))
    }
}
