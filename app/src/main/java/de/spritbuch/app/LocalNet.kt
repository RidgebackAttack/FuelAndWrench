package de.spritbuch.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Ab Android 17 brauchen Apps eine Erlaubnis, um Geräte im eigenen Netzwerk zu erreichen – etwa eine
 * Nextcloud zu Hause, deren Adresse auf eine 192.168…/10…-Adresse zeigt. Für Server im Internet
 * ist nichts nötig; gefragt wird deshalb nur, wenn der Server wirklich im lokalen Netz liegt.
 */
object LocalNet {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun granted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 37 || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Liegt der Server im lokalen Netz? (Namensauflösung über das Netz, daher im Hintergrund.) */
    suspend fun isLocal(url: String): Boolean = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url.trim().let { if ("://" in it) it else "https://$it" }).host }.getOrNull()
            ?: return@withContext false
        runCatching { InetAddress.getAllByName(host).any { isLocalAddress(it) } }.getOrDefault(false)
    }

    fun isLocalAddress(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress ||
            // IPv6 „Unique Local“ (fc00::/7)
            (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc)

    /** Fehlt die Erlaubnis für einen Server im lokalen Netz? */
    suspend fun missing(ctx: Context, url: String): Boolean = !granted(ctx) && isLocal(url)
}
