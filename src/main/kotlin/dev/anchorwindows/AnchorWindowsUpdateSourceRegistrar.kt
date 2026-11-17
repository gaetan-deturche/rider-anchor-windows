package dev.anchorwindows

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.updateSettings.impl.PluginUpdateSourceService
import com.intellij.openapi.updateSettings.impl.UpdateSettings

private val LOG = logger<AnchorWindowsUpdateSourceRegistrar>()

// Must equal the stored host string exactly - no trailing slash.
private const val UPDATE_REPOSITORY = "https://raw.githubusercontent.com/gaetan-deturche/rider-anchor-windows/main/updatePlugins.xml"

/**
 * Registers the plugin's custom update repository and records it as this plugin's "Updates from"
 * source, so updates need no manual Rider setup after the first disk install: Rider 2026.3 updates
 * a plugin only from its recorded source, and a disk-installed copy has none. Idempotent; never
 * overrides a source the user picked, never touches other repository entries. Both APIs live in an
 * impl package (treated as internal), hence the runCatching guards.
 */
class AnchorWindowsUpdateSourceRegistrar : AppLifecycleListener {
    override fun appStarted() {
        runCatching {
            val hosts = UpdateSettings.getInstance().storedPluginHosts
            if (UPDATE_REPOSITORY !in hosts) {
                hosts.add(UPDATE_REPOSITORY)
                LOG.info("registered the update repository")
            }
        }.onFailure { LOG.warn("could not register the update repository", it) }

        runCatching {
            if (PluginUpdateSourceService.isFunctionalitySupported()) {
                val sources = PluginUpdateSourceService.getInstance()
                val descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.anchorwindows"))
                if (descriptor != null && sources.isMissingPluginUpdateSource(descriptor)) {
                    sources.setPluginUpdateSourceId(
                        descriptor.pluginId,
                        sources.createCustomRepositoryPluginUpdateSourceId(UPDATE_REPOSITORY),
                    )
                    LOG.info("set this plugin's update source to the custom repository")
                }
            }
        }.onFailure { LOG.warn("could not set the plugin update source", it) }
    }
}
