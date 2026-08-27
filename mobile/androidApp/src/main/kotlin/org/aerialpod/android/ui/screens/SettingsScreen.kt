package org.aerialpod.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.Card
import org.aerialpod.android.ui.components.ScreenTitle
import org.aerialpod.android.ui.theme.ACCENT_PRESETS
import org.aerialpod.android.ui.theme.ThemeMode
import org.aerialpod.android.ui.theme.parseHexColor
import org.aerialpod.core.db.Repo
import org.aerialpod.core.downloads.DownloadPolicy

@Composable
fun SettingsScreen(graph: AppGraph) {
    val prefs by graph.theme.prefs.collectAsStateWithLifecycle()
    val peers by graph.core.lan.peers.collectAsStateWithLifecycle()
    val lanStatus by graph.core.lan.status.collectAsStateWithLifecycle()
    val busy by graph.actions.busy.collectAsStateWithLifecycle()

    var showAccount by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }
    var showEnterCode by remember { mutableStateOf(false) }
    var showManualPeer by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Android 17 enforces Local Network Protection: INTERNET no longer covers
    // reaching an address on the LAN, which is all device sync does. Asked for
    // here rather than at launch so the prompt lands while the user is pairing.
    var localNetworkGranted by remember { mutableStateOf(hasLocalNetwork(context)) }
    val askLocalNetwork = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkGranted = granted
        if (granted) graph.actions.dialPeersNow()
    }
    fun ensureLocalNetwork() {
        if (!localNetworkGranted && Build.VERSION.SDK_INT >= 36) {
            askLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    // `*/*` rather than `text/xml`: an .opml file's MIME type depends on the
    // provider that hands it over, and several report it as octet-stream —
    // which would leave the user's own export greyed out in the picker.
    val importOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()
            if (text.isNullOrBlank()) graph.actions.report("Could not read that file.")
            else graph.actions.importOpml(text)
        }
    }

    val exportOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val document = graph.actions.exportOpml()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(document.toByteArray()) }
                }
            }.onSuccess { graph.actions.report("Exported.") }
                .onFailure { graph.actions.report("Export failed.") }
        }
    }

    // Reloaded whenever a dialog closes, since those are what change it.
    val downloadAhead by remember(graph) { graph.library.downloadAhead }
        .collectAsStateWithLifecycle("1")
    val skipForward by remember(graph) { graph.library.stateLong(Repo.SKIP_FWD_SECS, 30) }
        .collectAsStateWithLifecycle(30L)
    val skipBack by remember(graph) { graph.library.stateLong(Repo.SKIP_BACK_SECS, 10) }
        .collectAsStateWithLifecycle(10L)
    val downloaded by remember(graph) { graph.library.downloaded }
        .collectAsStateWithLifecycle(emptyList())

    val snapshot by produceState<Snapshot?>(null, graph, showAccount, showEnterCode, showManualPeer) {
        value = withContext(Dispatchers.IO) {
            Snapshot(
                deviceId = graph.repo.lanDeviceId(),
                lanPort = graph.repo.lanPort(),
                lanEnabled = graph.repo.lanSyncEnabled(),
                account = graph.credentials.load()?.username,
                subscriptions = graph.repo.subscribedPodcasts().size,
                queued = graph.repo.queueEpisodeIds().size,
                knownPeers = graph.repo.knownPeers().size,
                manualPeers = graph.repo.manualPeers(),
                unmatched = graph.repo.unmatchedCount(),
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle("Settings")

        Section("Appearance") {
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.mode == mode,
                        onClick = { graph.theme.setMode(mode) },
                        label = { Text(mode.stateValue.replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            Text(
                "Accent",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ACCENT_PRESETS.forEach { (_, hex) ->
                    val rgb = parseHexColor(hex) ?: return@forEach
                    val selected = prefs.accent.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(rgb or 0xFF000000.toInt()))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { graph.theme.setAccent(hex) },
                    )
                }
            }
        }

        Section("gpodder.net") {
            val account = snapshot?.account
            Text(
                if (account != null) "Signed in as $account" else "Not signed in.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "This device registers its own gpodder device id. Sharing one with " +
                    "the desktop would make the server withhold each device's " +
                    "subscription changes from the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAccount = true }, enabled = !busy) {
                    Text(if (account != null) "Change account" else "Sign in")
                }
                if (account != null) {
                    OutlinedButton(onClick = { graph.actions.syncNow() }, enabled = !busy) {
                        Text("Sync now")
                    }
                }
            }
            if (account != null) {
                TextButton(onClick = { graph.actions.signOut(); showAccount = false }) {
                    Text("Sign out")
                }
            }
        }

        Section("Device sync") {
            Text(lanStatus, style = MaterialTheme.typography.bodyMedium)
            if (snapshot?.knownPeers == 0 && snapshot?.manualPeers?.isEmpty() == true) {
                // Worth saying plainly: a phone never listens and never sweeps
                // the subnet, so a code on its own gives it nothing to dial.
                Text(
                    "Setting up takes two things: the same pairing code on both " +
                        "devices, and this device's address — the phone dials out, " +
                        "so it has to be told where.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!localNetworkGranted && Build.VERSION.SDK_INT >= 36) {
                Text(
                    "This device has not allowed local network access, so it " +
                        "cannot reach your other devices. Tap Sync now to grant it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPairing = true }) { Text("Show code") }
                OutlinedButton(onClick = {
                    ensureLocalNetwork()
                    showEnterCode = true
                }) { Text("Enter a code") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ensureLocalNetwork()
                    showManualPeer = true
                }) { Text("Add an address") }
                OutlinedButton(onClick = {
                    ensureLocalNetwork()
                    graph.actions.dialPeersNow()
                }) { Text("Sync now") }
            }

            Field("This device", snapshot?.deviceId?.take(8)?.plus("…") ?: "…")
            Field("Peer port", snapshot?.lanPort?.toString() ?: "…")
            Field("Connected peers", peers.size.toString())
            Field("Known peers", snapshot?.knownPeers?.toString() ?: "…")

            peers.forEach { peer ->
                Field(peer.caption, peer.address)
            }
            snapshot?.manualPeers?.forEach { (address, port) ->
                Field("Manual", "$address:$port")
            }
        }

        Section("Playback") {
            SkipChoice(
                label = "Skip forward",
                seconds = skipForward,
                onPick = { graph.actions.setSkipSeconds(Repo.SKIP_FWD_SECS, it) },
            )
            SkipChoice(
                label = "Skip back",
                seconds = skipBack,
                onPick = { graph.actions.setSkipSeconds(Repo.SKIP_BACK_SECS, it) },
            )
            Text(
                "The same settings your desktop has. They are per-device, so " +
                    "changing one here does not change it there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Downloads") {
            Text("Download ahead", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadPolicy.CHOICES.forEach { choice ->
                    FilterChip(
                        selected = downloadAhead == choice,
                        onClick = { graph.actions.setDownloadAhead(choice) },
                        label = { Text(DownloadPolicy.label(choice)) },
                    )
                }
            }
            Text(
                "How much of the queue to keep on this device — a fixed number, " +
                    "or a share of however long the queue happens to be. A fraction " +
                    "rounds up, so a non-empty queue always keeps at least one. " +
                    "Everything else streams, and pinned episodes are never removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Completed and in-flight are counted separately: a bare "1
            // episode" while two more are actively transferring reads as
            // nothing happening.
            val complete = downloaded.count { it.download_state == "done" }
            val inFlight = downloaded.count { it.download_state == "downloading" }
            Field(
                "On this device",
                buildString {
                    append(if (complete == 1) "1 episode" else "$complete episodes")
                    if (inFlight > 0) append(", $inFlight downloading")
                },
            )
        }

        Section("Library") {
            Field("Subscriptions", snapshot?.subscriptions?.toString() ?: "…")
            Field("In the queue", snapshot?.queued?.toString() ?: "…")
            Field("Unmatched actions", snapshot?.unmatched?.toString() ?: "…")
            OutlinedButton(onClick = { graph.actions.refreshAll() }, enabled = !busy) {
                Text("Refresh all feeds")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importOpml.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text("Import OPML")
                }
                OutlinedButton(onClick = { exportOpml.launch("aerialpod.opml") }, enabled = !busy) {
                    Text("Export OPML")
                }
            }
            Text(
                "The fastest way to bring a library over from the desktop before " +
                    "device sync can dial.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(modifier = Modifier.padding(bottom = 24.dp))
    }

    if (showAccount) {
        AccountDialog(
            initialUsername = snapshot?.account.orEmpty(),
            onDismiss = { showAccount = false },
            onSave = { user, pass, server ->
                showAccount = false
                graph.actions.saveAccount(user, pass, server)
            },
        )
    }

    if (showPairing) {
        PairingCodeDialog(graph) { showPairing = false }
    }

    if (showEnterCode) {
        EnterCodeDialog(graph) { showEnterCode = false }
    }

    if (showManualPeer) {
        ManualPeerDialog(graph, snapshot?.lanPort ?: 47722) { showManualPeer = false }
    }
}

private data class Snapshot(
    val deviceId: String,
    val lanPort: Int,
    val lanEnabled: Boolean,
    val account: String?,
    val subscriptions: Int,
    val queued: Int,
    val knownPeers: Int,
    val manualPeers: List<Pair<String, Int>>,
    val unmatched: Long,
)

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(content = content)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
        )
    }
}

@Composable
private fun AccountDialog(
    initialUsername: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("https://gpodder.net") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("gpodder.net") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(username, password, server) },
                enabled = username.isNotBlank() && password.isNotEmpty(),
            ) { Text("Sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairingCodeDialog(graph: AppGraph, onDismiss: () -> Unit) {
    // Generated on first read, so opening this dialog is what creates the
    // device's pairing secret if it does not have one yet.
    val code by produceState<String?>(null, graph) {
        value = withContext(Dispatchers.IO) { graph.core.pairing.pairingCode() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pairing code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    code ?: "…",
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Type this into your other device. It is the only credential " +
                        "the peer channel has, so treat it like a password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun EnterCodeDialog(graph: AppGraph, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter a pairing code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    label = { Text("Code from your other device") },
                    isError = error != null,
                    singleLine = true,
                )
                Text(
                    error ?: "Adopting a code replaces this device's own, so anything " +
                        "already paired with it stops matching. Pairing dials straight " +
                        "away — add the other device's address first if it has never " +
                        "connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank(),
                onClick = {
                    error = graph.actions.pairWithCode(code)
                    if (error == null) onDismiss()
                },
            ) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManualPeerDialog(graph: AppGraph, defaultPort: Int, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(defaultPort.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a peer address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "The phone never listens, so it has to know where to dial. " +
                        "Ports differ per install — check the desktop's setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank() && port.isNotBlank(),
                onClick = {
                    graph.actions.addPeerAddress(address, port.toIntOrNull() ?: defaultPort)
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * `ACCESS_LOCAL_NETWORK`, by name rather than constant.
 *
 * It does not exist below the release that introduced it, and referring to the
 * constant would tie compilation to a specific SDK for a string.
 */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private fun hasLocalNetwork(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 36 ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** The handful of skip lengths worth a tap. */
internal val SKIP_CHOICES = listOf(5L, 10L, 15L, 30L, 45L, 60L)

/**
 * The chips to offer when the current value is [seconds].
 *
 * Separate from the composable so it can be tested: the whole point of the rule
 * is the case that is awkward to reach through the UI, where the desktop has
 * set a value this list does not offer.
 */
internal fun skipChoices(seconds: Long): List<Long> =
    (SKIP_CHOICES + seconds).distinct().sorted()

/**
 * Skip length, as chips.
 *
 * The desktop uses a spin box over 5–300 s, so a value set there can be one this
 * list does not offer. Rather than show nothing selected — which reads as "not
 * configured" — the current value joins the list, in order.
 */
@Composable
private fun SkipChoice(label: String, seconds: Long, onPick: (Long) -> Unit) {
    Text("$label — ${seconds}s", style = MaterialTheme.typography.labelLarge)
    val choices = remember(seconds) { skipChoices(seconds) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { value ->
            FilterChip(
                selected = value == seconds,
                onClick = { onPick(value) },
                label = { Text("${value}s") },
            )
        }
    }
}
