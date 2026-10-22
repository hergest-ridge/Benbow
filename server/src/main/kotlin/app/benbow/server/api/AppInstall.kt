package app.benbow.server.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.request.host
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The first install of the Android app from its server (ADR-042, as built 7.19 U4): a phone with no app yet (so no
 * device token) opens `/install` on the home network, by the QR code on the admin page or by the address, and downloads
 * the signed APK the package carries. The page says what Android and Play Protect will ask and what to type in the app
 * after; it shows nothing of the journal. Updates after that come through the app itself (U2).
 */
internal fun Route.appInstallRoutes(ctx: ServerContext) {
    get("/install") {
        call.respondAdmin(installPage(ctx, call.request.host()))
    }

    get("/install/benbow.apk") {
        val app = ctx.app ?: return@get call.respond(HttpStatusCode.NotFound, "This server carries no app")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${app.file.fileName}\"")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(LocalPathContent(app.file, ContentType("application", "vnd.android.package-archive")))
    }
}

private fun installPage(ctx: ServerContext, host: String): String {
    val body = buildString {
        append("<section id=\"install\" class=\"page\">")
        append("<h1 class=\"page-title\"><img class=\"logo\" src=\"/admin/assets/icon.svg\" alt=\"\">Benbow для Android</h1>")
        append("<p class=\"page-lead\">Дневник на телефоне; записи хранятся на этом компьютере.</p>")
        append("<div class=\"card install\">")
        val app = ctx.app?.info
        if (app == null) {
            append("<p class=\"lead\">На этом сервере нет приложения для Android.</p>")
        } else {
            val mb = "%.0f".format(app.size / 1_048_576.0)
            append("<div class=\"row\"><a class=\"btn\" href=\"/install/benbow.apk\" download>${icon("download")}")
            append("<span>Скачать Benbow ${esc(app.versionName)} ($mb МБ)</span></a></div>")
            append("<ol class=\"steps\">")
            append("<li>Откройте скачанный файл. Android спросит, можно ли этому браузеру устанавливать приложения: разрешите.</li>")
            append(
                "<li>Google Play Protect может предложить проверить незнакомое ему приложение: можно согласиться или выбрать " +
                    "«Install without scanning» в «More details».</li>",
            )
            append(
                "<li>Откройте Benbow и впишите адрес этого компьютера <b>${esc(host)}</b> и порт <b>${ctx.port}</b>. " +
                    "Код подключения покажет админ-страница сервера на этом компьютере.</li>",
            )
            append("</ol>")
            append("<p class=\"muted\">Дальше приложение обновляется само с этого сервера (кнопка «Обновить» в «Ещё»). ")
            append("SHA-256 файла: <span class=\"mono break\">${esc(app.sha256)}</span></p>")
        }
        append("</div></section>")
    }
    return adminShell(emptyList(), body, null, versionLine(), drawer = false)
}

/** «Телефон без приложения»: a QR code of this computer's `/install` page on the home network, for the admin page. */
internal fun installCard(ctx: ServerContext, addresses: List<String> = lanAddresses()): String = buildString {
    if (ctx.app == null) return@buildString
    append("<div class=\"card\"><h3>${icon("phone")}Телефон без приложения</h3>")
    val first = addresses.firstOrNull()
    if (first == null) {
        append("<p class=\"muted\">Этот компьютер сейчас не видит домашней сети: адрес для телефона не найден.</p>")
    } else {
        val url = "https://$first:${ctx.port}/install"
        append("<p class=\"lead\">Наведите камеру телефона на код: откроется страница, где можно скачать Benbow ")
        append("${esc(ctx.app.info.versionName)}. Браузер один раз предупредит о сертификате этого сервера.</p>")
        append("<div class=\"qr\">${qrSvg(url)}</div>")
        append("<p class=\"mono center\">${esc(url)}</p>")
        if (addresses.size > 1) {
            append("<p class=\"muted\">Другие адреса этого компьютера: ${addresses.drop(1).joinToString { esc(it) }}</p>")
        }
    }
    append("</div>")
}

/** [text] as a QR code in SVG: one path of dark modules with a quiet zone, scaled by its viewBox (no styles: the CSP). */
internal fun qrSvg(text: String): String {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
    )
    val path = buildString {
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) if (matrix[x, y]) append("M$x ${y}h1v1h-1z")
        }
    }
    return "<svg class=\"qr-code\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\" role=\"img\" aria-label=\"QR-код: ${esc(text)}\" " +
        "shape-rendering=\"crispEdges\"><rect width=\"${matrix.width}\" height=\"${matrix.height}\" fill=\"#fff\"/>" +
        "<path d=\"$path\" fill=\"#000\"/></svg>"
}

/**
 * This computer's IPv4 addresses on its home network (private ranges), the one a phone there most likely reaches first:
 * the address the system would send from towards the internet (a UDP socket "connected" to a public address picks it;
 * nothing is sent). Adapters of virtual machines and WSL are left out (Java can't tell them apart by itself).
 */
internal fun lanAddresses(): List<String> {
    val primary = runCatching {
        DatagramSocket().use {
            it.connect(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), 53)
            it.localAddress
        }
    }.getOrNull()?.takeIf { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    val others = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !VIRTUAL.containsMatchIn(it.displayName.orEmpty()) }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
    return (listOfNotNull(primary) + others).distinct()
}

private val VIRTUAL = Regex("vEthernet|VMware|VirtualBox|Hyper-V|WSL|Docker|vpn|TAP-|Npcap", RegexOption.IGNORE_CASE)
