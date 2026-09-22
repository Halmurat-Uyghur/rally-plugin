package com.github.halmurat.rally

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the plugin's identity in plugin.xml. The IDE matches an installed plugin to an update by
 * `<id>`, and settings search / "Show Settings" links address the page by its configurable `id`.
 * Neither is derived from the Kotlin package: a package rename once rewrote both, which made the
 * new build install as a second plugin alongside the running one (duplicate "Rally" tool window,
 * notification group and settings component until restart). Same rule as
 * [com.github.halmurat.rally.settings.RallySettingsPersistenceKeysTest]: these stay frozen.
 */
class PluginDescriptorIdentityTest {

    private val descriptor = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/resources/META-INF/plugin.xml"))

    @Test
    fun `plugin id is frozen at the original id`() {
        val id = descriptor.documentElement.getElementsByTagName("id").item(0).textContent.trim()
        assertEquals("com.github.halmuratuyghur.rally", id)
    }

    @Test
    fun `settings configurable id is frozen at the original id`() {
        val configurable = descriptor.getElementsByTagName("applicationConfigurable").item(0)
        val id = configurable.attributes.getNamedItem("id").nodeValue
        assertEquals("com.github.halmuratuyghur.rally.settings", id)
    }
}
