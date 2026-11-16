package de.spritbuch.app

import android.net.Uri
import android.util.Xml
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Pfad-Segmente URL-kodieren, Schrägstriche bleiben erhalten. */
fun encodePath(path: String): String = path.split("/").filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

object Nextcloud {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private const val UA = "Fuel & Wrench (Android)"

    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    /** Prüft über status.php, ob unter der Adresse eine Nextcloud läuft. */
    suspend fun isNextcloud(server: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$server/status.php").header("User-Agent", UA).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                val o = Json.parseToJsonElement(r.body?.string() ?: "").jsonObject
                o["installed"]?.jsonPrimitive?.booleanOrNull == true
            }
        }.getOrDefault(false)
    }

    fun davRoot(server: String, user: String) = "$server/remote.php/dav/files/${Uri.encode(user)}"

    data class LoginStart(val loginUrl: String, val token: String, val endpoint: String)
    data class LoginResult(val loginName: String, val appPassword: String)

    /** Nextcloud Login Flow v2: liefert die Anmeldeseite für den Browser und die Daten zum Abfragen. */
    suspend fun startLogin(server: String): LoginStart = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$server/index.php/login/v2")
            .header("User-Agent", UA)
            .post(FormBody.Builder().build())
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException(httpMessage(r.code))
            val o = Json.parseToJsonElement(r.body?.string() ?: "").jsonObject
            val poll = o["poll"]!!.jsonObject
            LoginStart(
                loginUrl = o["login"]!!.jsonPrimitive.content,
                token = poll["token"]!!.jsonPrimitive.content,
                endpoint = poll["endpoint"]!!.jsonPrimitive.content,
            )
        }
    }

    /** Fragt ab, ob die Anmeldung im Browser abgeschlossen ist (sonst null). */
    suspend fun poll(start: LoginStart): LoginResult? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(start.endpoint)
                .header("User-Agent", UA)
                .post(FormBody.Builder().add("token", start.token).build())
                .build()
            http.newCall(req).execute().use { r ->
                if (r.code != 200) return@runCatching null
                val o = Json.parseToJsonElement(r.body?.string() ?: "").jsonObject
                LoginResult(o["loginName"]!!.jsonPrimitive.content, o["appPassword"]!!.jsonPrimitive.content)
            }
        }.getOrNull()
    }
}

/** Unterordner eines WebDAV-Ordners auflisten (PROPFIND, Depth 1). */
fun WebDavClient.listFolders(path: String): List<String> {
    val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
    val res = propfind(path, body)
    if (res.code !in 200..299) throw java.io.IOException(httpMessage(res.code))
    val xml = res.body?.decodeToString() ?: return emptyList()

    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(xml.reader())
    }
    val entries = mutableListOf<Pair<String, Boolean>>()
    var href: String? = null
    var isDir = false
    var inHref = false
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "response" -> { href = null; isDir = false }
                "href" -> inHref = true
                "collection" -> isDir = true
            }
            XmlPullParser.TEXT -> if (inHref) href = (href ?: "") + parser.text
            XmlPullParser.END_TAG -> when (parser.name) {
                "href" -> inHref = false
                "response" -> href?.let { entries += it to isDir }
            }
        }
    }
    // Erster Eintrag ist der Ordner selbst
    return entries.drop(1).filter { it.second }.map { (h, _) ->
        URLDecoder.decode(h.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
    }.filter { it.isNotEmpty() && !it.startsWith(".") }.sortedBy { it.lowercase() }
}

@Composable
fun FolderPickerDialog(
    dav: WebDavClient,
    initial: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(initial.trim('/')) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(path, reload) {
        loading = true
        error = null
        withContext(Dispatchers.IO) { runCatching { dav.listFolders(path) } }
            .onSuccess { folders = it }
            .onFailure {
                folders = emptyList()
                error = it.message ?: str(R.string.folder_read_error)
            }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.choose_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "/" + path,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (path.isNotEmpty()) {
                        FolderRow(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.parent_folder)) {
                            path = path.substringBeforeLast('/', "")
                        }
                    }
                    when {
                        loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(28.dp))
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        folders.isEmpty() -> Text(str(R.string.no_subfolders), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        else -> folders.forEach { f ->
                            FolderRow(Icons.Default.Folder, f) { path = if (path.isEmpty()) f else "$path/$f" }
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName, { newName = it.replace("/", "") },
                        label = { Text(str(R.string.new_folder)) }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val target = if (path.isEmpty()) newName.trim() else "$path/${newName.trim()}"
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { dav.ensureDir(target) } }
                                    .onSuccess { newName = ""; path = target; reload++ }
                                    .onFailure { error = it.message }
                            }
                        },
                    ) { Icon(Icons.Default.CreateNewFolder, contentDescription = str(R.string.create_folder)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text(str(R.string.use_this_folder)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
