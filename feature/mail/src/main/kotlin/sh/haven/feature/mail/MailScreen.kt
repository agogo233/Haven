package sh.haven.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.mail.MailEngine
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only Proton mail client (v1): folder list → message list → reader. The
 * reader shows plain text only (see [ParsedMessage.bodyText]) — no WebView, so
 * remote images/scripts never load. No compose/send in v1.
 *
 * Mirrors the rclone→SFTP pattern: the connect happens in ConnectionsViewModel;
 * this screen consumes an already-connected session via [pendingProfileId].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    pendingProfileId: String? = null,
    mailModifier: Modifier = Modifier,
    viewModel: MailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(pendingProfileId) {
        viewModel.setPendingEmailProfile(pendingProfileId)
    }

    // Compose/reply/forward overlays everything as a full-screen pane (CP-7).
    ui.compose?.let { draft ->
        ComposeView(
            draft = draft,
            accounts = ui.accounts,
            onTo = viewModel::updateTo,
            onCc = viewModel::updateCc,
            onBcc = viewModel::updateBcc,
            onSubject = viewModel::updateSubject,
            onBody = viewModel::updateBody,
            onToggleCcBcc = viewModel::toggleCcBcc,
            onFromSelected = viewModel::setComposeFrom,
            onSend = viewModel::send,
            onDiscard = viewModel::discardCompose,
            modifier = mailModifier,
        )
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val sentMessage = stringResource(R.string.mail_sent_ok)
    LaunchedEffect(ui.sentSignal) {
        if (ui.sentSignal > 0) snackbarHostState.showSnackbar(sentMessage)
    }

    val title = when (ui.view) {
        MailViewModel.View.FOLDERS -> "Mail"
        MailViewModel.View.MESSAGES -> ui.selectedFolder?.name ?: "Mail"
        MailViewModel.View.READER -> ui.openMessage?.subject ?: "Message"
        MailViewModel.View.COMPOSE -> ""
    }

    Scaffold(
        modifier = mailModifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (ui.view == MailViewModel.View.FOLDERS) {
                        AccountSwitcher(ui.accounts, ui.activeAccount, viewModel::selectAccount)
                    } else {
                        // Folder name / subject, with the active account as a subtitle so it's
                        // always clear which account's mail you're reading.
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ui.activeAccount?.let { acc ->
                                Text(
                                    acc.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (ui.view != MailViewModel.View.FOLDERS) {
                        IconButton(onClick = {
                            when (ui.view) {
                                MailViewModel.View.READER -> viewModel.closeMessage()
                                MailViewModel.View.MESSAGES -> viewModel.backToFolders()
                                else -> {}
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    when (ui.view) {
                        MailViewModel.View.FOLDERS, MailViewModel.View.MESSAGES -> {
                            if (ui.canCompose) {
                                IconButton(onClick = viewModel::startCompose) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.mail_compose))
                                }
                            }
                        }
                        MailViewModel.View.READER -> {
                            val open = ui.openMessage
                            val attribution = if (open != null) {
                                stringResource(
                                    R.string.mail_reply_quote_header,
                                    open.dateMillis?.let { formatDate(it) } ?: "",
                                    open.from,
                                )
                            } else {
                                ""
                            }
                            val forwardHeader = stringResource(R.string.mail_forward_header)
                            IconButton(onClick = { viewModel.startReply(replyAll = false, attributionLine = attribution) }) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.mail_reply))
                            }
                            IconButton(onClick = { viewModel.startReply(replyAll = true, attributionLine = attribution) }) {
                                Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = stringResource(R.string.mail_reply_all))
                            }
                            IconButton(onClick = { viewModel.startForward(forwardHeader = forwardHeader) }) {
                                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.mail_forward))
                            }
                        }
                        else -> {}
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.error != null && ui.folders.isEmpty() -> CenterText(ui.error!!)
                ui.loading && ui.view == MailViewModel.View.FOLDERS && ui.folders.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> when (ui.view) {
                    MailViewModel.View.FOLDERS -> FolderList(ui.folders, viewModel::openFolder)
                    MailViewModel.View.MESSAGES -> MessageList(ui.messages, ui.loading, viewModel::openMessage)
                    MailViewModel.View.READER -> ui.openMessage?.let { MessageReader(it) }
                    MailViewModel.View.COMPOSE -> {} // handled by the early full-screen branch above
                }
            }
            if (ui.error != null && ui.folders.isNotEmpty()) {
                // Non-fatal error banner over content.
                Text(
                    ui.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderList(folders: List<MailFolder>, onOpen: (MailFolder) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(folders, key = { it.id }) { folder ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(folder) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            }
            Divider()
        }
    }
}

@Composable
private fun MessageList(messages: List<MailMessage>, loading: Boolean, onOpen: (MailMessage) -> Unit) {
    if (messages.isEmpty() && loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (messages.isEmpty()) {
        CenterText("No messages")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { msg ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(msg) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        msg.from?.let { if (it.name.isNotBlank()) it.name else it.address } ?: "(unknown)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (msg.unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (msg.numAttachments > 0) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "Has attachments",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    msg.subject.ifBlank { "(no subject)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (msg.unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (msg.timeSeconds > 0) {
                    Text(
                        formatDate(msg.timeSeconds * 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Divider()
        }
    }
}

@Composable
private fun MessageReader(msg: ParsedMessage) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(msg.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(msg.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (msg.to.isNotEmpty()) {
            Text(
                "To: ${msg.to.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        msg.dateMillis?.let {
            Text(formatDate(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (msg.attachments.isNotEmpty()) {
            Divider(Modifier.padding(vertical = 8.dp))
            msg.attachments.forEach { att ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(att.filename, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Divider(Modifier.padding(vertical = 8.dp))
        if (msg.bodyWasHtml) {
            Text(
                "(HTML message shown as text — remote content blocked)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(msg.bodyText, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Full-screen compose pane (portrait: no FAB, one pane at a time). Send lives in
 * the app bar with an in-flight spinner; Close/system-back confirm a discard when
 * the draft is dirty. Send failures stay in-form ([ComposeDraft.sendError]); send
 * success is signalled by the parent's snackbar after this pane closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeView(
    draft: ComposeDraft,
    accounts: List<MailViewModel.MailAccount>,
    onTo: (String) -> Unit,
    onCc: (String) -> Unit,
    onBcc: (String) -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onToggleCcBcc: () -> Unit,
    onFromSelected: (String) -> Unit,
    onSend: (String) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val recipientsRequired = stringResource(R.string.mail_recipients_required)

    fun attemptDiscard() {
        if (draft.isDirty) showDiscardConfirm = true else onDiscard()
    }

    BackHandler { attemptDiscard() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.mail_compose), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { attemptDiscard() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mail_close))
                    }
                },
                actions = {
                    if (draft.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = { onSend(recipientsRequired) },
                            enabled = draft.to.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.mail_send))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ComposeFromRow(accounts, draft.fromProfileId, onFromSelected)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.to,
                onValueChange = onTo,
                label = { Text(stringResource(R.string.mail_to)) },
                singleLine = true,
                isError = draft.sendError != null,
                supportingText = {
                    Text(draft.sendError ?: stringResource(R.string.mail_recipients_hint))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                trailingIcon = {
                    TextButton(onClick = onToggleCcBcc) { Text(stringResource(R.string.mail_cc_bcc)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.showCcBcc) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.cc,
                    onValueChange = onCc,
                    label = { Text(stringResource(R.string.mail_cc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.bcc,
                    onValueChange = onBcc,
                    label = { Text(stringResource(R.string.mail_bcc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.subject,
                onValueChange = onSubject,
                label = { Text(stringResource(R.string.mail_subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.body,
                onValueChange = onBody,
                label = { Text(stringResource(R.string.mail_message)) },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.mail_discard_title)) },
            text = { Text(stringResource(R.string.mail_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDiscard()
                }) { Text(stringResource(R.string.mail_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.mail_discard_cancel))
                }
            },
        )
    }
}

/** Folder-view title: the active account, with a dropdown to switch when several are connected. */
@Composable
private fun AccountSwitcher(
    accounts: List<MailViewModel.MailAccount>,
    active: MailViewModel.MailAccount?,
    onSelect: (String) -> Unit,
) {
    val label = active?.label ?: "Mail"
    if (accounts.size <= 1) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.mail_switch_account))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.label} · ${engineLabel(acc.engine)}") },
                    onClick = {
                        expanded = false
                        onSelect(acc.profileId)
                    },
                    leadingIcon = if (acc.profileId == active?.profileId) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Compose "From" row: names the sending account; a dropdown when more than one is connected. */
@Composable
private fun ComposeFromRow(
    accounts: List<MailViewModel.MailAccount>,
    fromProfileId: String,
    onSelect: (String) -> Unit,
) {
    val selected = accounts.firstOrNull { it.profileId == fromProfileId }
    val selectedText = selected?.let { "${it.label} · ${engineLabel(it.engine)}" } ?: ""
    val multi = accounts.size > 1
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.mail_from),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (multi) Modifier.clickable { expanded = true } else Modifier)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (multi) Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.mail_switch_account))
            }
            if (multi) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    accounts.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.label} · ${engineLabel(acc.engine)}") },
                            onClick = {
                                expanded = false
                                onSelect(acc.profileId)
                            },
                            leadingIcon = if (acc.profileId == fromProfileId) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        Divider()
    }
}

/** Short engine tag shown next to an account label (Proton vs generic IMAP). */
@Composable
private fun engineLabel(engine: MailEngine): String = when (engine) {
    MailEngine.PROTON -> stringResource(R.string.mail_engine_proton)
    MailEngine.IMAP -> stringResource(R.string.mail_engine_imap)
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
