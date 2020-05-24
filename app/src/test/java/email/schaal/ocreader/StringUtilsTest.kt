package email.schaal.ocreader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import email.schaal.ocreader.database.model.Item
import email.schaal.ocreader.util.cleanString
import email.schaal.ocreader.util.getByLine
import email.schaal.ocreader.util.getTimeSpanString
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

/**
 * Test class for StringUtils
 */
@RunWith(AndroidJUnit4::class)
class StringUtilsTest {
    @Test
    @Throws(Exception::class)
    fun testGetByLine() {
        val item = Item()
        Assert.assertEquals("", getByLine(ApplicationProvider.getApplicationContext(), "<p class=\"byline\">%s</p>", null, item.feed))
        Assert.assertEquals("<p class=\"byline\">by testAuthor</p>", getByLine(ApplicationProvider.getApplicationContext(), "<p class=\"byline\">%s</p>", "testAuthor", item?.feed))
    }

    @Test
    @Throws(Exception::class)
    fun testCleanString() {
        val html = "<span>Test</span>"
        val entity = "Test &gt; Test"
        Assert.assertEquals("Test", html.cleanString())
        Assert.assertEquals("Test > Test", entity.cleanString())
    }
}