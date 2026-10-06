package zed.rainxch.core.data.network

import zed.rainxch.core.domain.model.settings.ProxyConfig
import zed.rainxch.core.domain.system.DesktopOs
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Resolves the proxy the desktop OS is configured with. The JDK's own [ProxySelector] only reads
 * `http.proxyHost`-style system properties unless the JVM was started with
 * `-Djava.net.useSystemProxies=true`, so `ProxyConfig.System` would silently mean "direct" for
 * users who enabled the proxy in Windows/macOS/GNOME network settings.
 */
object SystemProxyResolver {

    private const val CACHE_TTL_MS = 10_000L
    private const val COMMAND_TIMEOUT_MS = 4_000L

    private val probeUri: URI = URI.create("https://translate.googleapis.com")

    private data class Snapshot(
        val proxy: ProxyConfig.Http?,
        val bypass: List<String>,
    )

    @Volatile private var cachedAt: Long = 0L

    @Volatile private var cached: Snapshot? = null

    fun selector(): ProxySelector? {
        val snapshot = snapshot()
        val config = snapshot.proxy ?: return null
        val address = InetSocketAddress.createUnresolved(config.host, config.port)
        return OsProxySelector(Proxy(Proxy.Type.HTTP, address), snapshot.bypass)
    }

    private fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        val resolved = runCatching { resolveUncached() }.getOrDefault(Snapshot(null, emptyList()))
        cached = resolved
        cachedAt = now
        return resolved
    }

    private fun resolveUncached(): Snapshot {
        systemPropertySnapshot()?.let { return it }
        if (DesktopOs.isWindows) windowsSnapshot()?.let { return it }
        if (DesktopOs.isMac) macOsSnapshot()?.let { return it }
        if (DesktopOs.isLinux) linuxSnapshot()?.let { return it }
        return Snapshot(proxyFromProxySelector(), emptyList())
    }

    private fun systemPropertySnapshot(): Snapshot? {
        val proxy = hostPortFromSystemProperties() ?: return null
        val bypass =
            System.getProperty("http.nonProxyHosts")
                ?.split('|')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .orEmpty()
        return Snapshot(proxy, bypass)
    }

    private fun hostPortFromSystemProperties(): ProxyConfig.Http? {
        for (prefix in listOf("https", "http")) {
            val host = System.getProperty("$prefix.proxyHost")?.trim()?.takeIf { it.isNotEmpty() }
            val port =
                System.getProperty("$prefix.proxyPort")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            if (host != null && port != null) return ProxyConfig.Http(host, port)
        }
        return null
    }

    private fun windowsSnapshot(): Snapshot? {
        val output =
            runCommand(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            ) ?: return null
        val values = mutableMapOf<String, String>()
        REGISTRY_LINE.findAll(output).forEach { match ->
            values[match.groupValues[1]] = match.groupValues[2].trim()
        }
        val enabled = values["ProxyEnable"]?.let { it.equals("0x1", true) || it == "1" } ?: false
        if (!enabled) return null
        val server = values["ProxyServer"]?.takeIf { it.isNotBlank() } ?: return null
        val proxy = parseWindowsProxyServer(server) ?: return null
        val bypass =
            values["ProxyOverride"]
                ?.split(';')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .orEmpty()
        return Snapshot(proxy, bypass)
    }

    private fun parseWindowsProxyServer(raw: String): ProxyConfig.Http? {
        if (!raw.contains('=')) return parseHostPort(raw)?.let { (host, port) -> ProxyConfig.Http(host, port) }
        val entries =
            raw.split(';').mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    entry.substring(0, separator).trim().lowercase() to entry.substring(separator + 1).trim()
                }
            }.toMap()
        for (scheme in listOf("https", "http", "socks5", "socks")) {
            val value = entries[scheme]?.takeIf { it.isNotEmpty() } ?: continue
            parseHostPort(value)?.let { (host, port) -> return ProxyConfig.Http(host, port) }
        }
        return null
    }

    private fun macOsSnapshot(): Snapshot? {
        val output = runCommand("scutil", "--proxy") ?: return null
        val values = mutableMapOf<String, String>()
        MAC_LINE.findAll(output).forEach { match ->
            values[match.groupValues[1].trim()] = match.groupValues[2].trim()
        }
        for (prefix in listOf("HTTPS", "HTTP", "SOCKS")) {
            if (values["${prefix}Enable"] != "1") continue
            val host = values["${prefix}Proxy"]?.takeIf { it.isNotBlank() } ?: continue
            val port =
                values["${prefix}Port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
            val bypass = macOsExceptions(output)
            return Snapshot(ProxyConfig.Http(host, port), bypass)
        }
        return null
    }

    private fun macOsExceptions(output: String): List<String> {
        val block = output.substringAfter("ExceptionsList", "")
        return MAC_EXCEPTION_LINE.findAll(block)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun linuxSnapshot(): Snapshot? {
        val noProxy =
            System.getenv("no_proxy")?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.orEmpty()
        for (key in listOf("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY", "all_proxy", "ALL_PROXY")) {
            val value = System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            parseHostPort(value)?.let { (host, port) -> return Snapshot(ProxyConfig.Http(host, port), noProxy) }
        }
        if (gsettings("org.gnome.system.proxy", "mode")?.trim('\'', '"') != "manual") return null
        for (schema in listOf("https", "http")) {
            val host = gsettings("org.gnome.system.proxy.$schema", "host")?.trim('\'', '"') ?: continue
            val port = gsettings("org.gnome.system.proxy.$schema", "port")?.trim()?.toIntOrNull() ?: continue
            if (host.isBlank() || port !in 1..65535) continue
            return Snapshot(ProxyConfig.Http(host, port), noProxy)
        }
        return null
    }

    private fun gsettings(schema: String, key: String): String? =
        runCommand("gsettings", "get", schema, key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun proxyFromProxySelector(): ProxyConfig.Http? =
        runCatching {
            ProxySelector
                .getDefault()
                ?.select(probeUri)
                ?.firstOrNull { it.type() == Proxy.Type.HTTP }
                ?.address()
                ?.let { address ->
                    if (address !is InetSocketAddress) return@let null
                    val port = address.port
                    val host = address.hostString?.takeIf { it.isNotBlank() } ?: address.hostName
                    if (host.isNullOrBlank() || port !in 1..65535) null else ProxyConfig.Http(host, port)
                }
        }.getOrNull()

    private fun parseHostPort(raw: String): Pair<String, Int>? {
        var value = raw.trim()
        value = value.substringAfter("://", value)
        value =
            value
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
        if (value.isEmpty()) return null

        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close <= 0) return null
            host = value.substring(1, close)
            portText = value.substring(close + 1).removePrefix(":")
        } else {
            val separator = value.lastIndexOf(':')
            if (separator <= 0) return null
            host = value.substring(0, separator)
            portText = value.substring(separator + 1)
        }

        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val cleanHost = host.trim().takeIf { it.isNotEmpty() } ?: return null
        return cleanHost to port
    }

    private fun runCommand(vararg command: String): String? =
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                output
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private class OsProxySelector(
        private val proxy: Proxy,
        private val bypass: List<String>,
    ) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host.orEmpty().lowercase()
            return if (host.isBlank() || isBypassed(host)) listOf(Proxy.NO_PROXY) else listOf(proxy)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

        private fun isBypassed(host: String): Boolean {
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            return bypass.any { entry -> matchesBypass(host, entry.lowercase()) }
        }

        private fun matchesBypass(host: String, entry: String): Boolean {
            if (entry.isEmpty() || entry.contains('/') || entry.contains(':')) return false
            if (entry == "<local>") return !host.contains('.')
            if (entry.endsWith("*")) {
                val prefix = entry.dropLast(1).trimStart('*', '.')
                return prefix.isNotEmpty() && host.startsWith(prefix)
            }
            val pattern = entry.removePrefix("*.").removePrefix(".")
            if (pattern.isEmpty()) return false
            return host == pattern || host.endsWith(".$pattern")
        }
    }

    private val REGISTRY_LINE = Regex("^\\s+(\\S+)\\s+REG_[A-Z_]+\\s+(.*)$", RegexOption.MULTILINE)

    private val MAC_LINE = Regex("^\\s*([A-Za-z]+)\\s*:\\s*(\\S+)\\s*$", RegexOption.MULTILINE)

    private val MAC_EXCEPTION_LINE = Regex("^\\s*\\d+\\s*:\\s*(\\S+)\\s*$", RegexOption.MULTILINE)
}
