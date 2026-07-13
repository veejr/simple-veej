package org.veejr.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import kotlinx.coroutines.delay

private enum class HomeTab(val title: String, val icon: ImageVector) {
    MESSAGES("Messages", Icons.AutoMirrored.Outlined.Chat),
    CONTACTS("Contacts", Icons.Outlined.People),
    GROUPS("Groups", Icons.Outlined.Groups),
    ACCOUNT("Account", Icons.Outlined.Settings),
}

internal data class ConversationTarget(
    val subjectType: String,
    val id: String,
    val title: String,
    val memberHandles: Set<String>,
    val initials: String? = null,
)

@Composable
fun HomeScreen(
    state: AppUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onSend: (String, String, String) -> Unit,
    onSetDeliveryPolicy: (String, String, String?) -> Unit,
    onSavePrivateNote: (String, String, String) -> Unit,
    onLoadMoreHistory: () -> Unit,
    onLogout: () -> Unit,
    onChangeInstance: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.MESSAGES) }
    var conversationType by rememberSaveable { mutableStateOf<String?>(null) }
    var conversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountHistoryOpen by rememberSaveable { mutableStateOf(false) }
    val conversation = when (conversationType) {
        "self" -> state.account?.takeIf { it.id == conversationId }?.let {
            ConversationTarget("self", it.id, "Notes to yourself", setOf(it.handle), "ME")
        }
        "contact" -> state.contacts.firstOrNull { it.id == conversationId }?.let {
            ConversationTarget("contact", it.id, it.handle, setOf(it.handle))
        }
        "group" -> state.groups.firstOrNull { it.id == conversationId }?.let {
            ConversationTarget("group", it.id, it.name, it.members.map { member -> member.handle }.toSet())
        }
        else -> null
    }
    val openConversation: (String, String) -> Unit = { type, id ->
        conversationType = type
        conversationId = id
        tab = HomeTab.MESSAGES
    }
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            onSync()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MobileTopBar(tab, state, conversation, accountHistoryOpen, onRefresh) {
                if (accountHistoryOpen) {
                    accountHistoryOpen = false
                } else {
                    conversationType = null
                    conversationId = null
                }
            }
        },
        bottomBar = {
            if (!keyboardVisible && !accountHistoryOpen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    HomeTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                tab = item
                                accountHistoryOpen = false
                            },
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.MESSAGES -> if (conversation == null) {
                MessagesScreen(state, padding, onAccept, onDecline, openConversation)
            } else {
                ConversationScreen(state, conversation, padding, onSend)
            }
            HomeTab.CONTACTS -> ContactsScreen(
                state,
                padding,
                onSetDeliveryPolicy,
                onSavePrivateNote,
                openConversation,
            )
            HomeTab.GROUPS -> GroupsScreen(
                state,
                padding,
                onSetDeliveryPolicy,
                onSavePrivateNote,
                openConversation,
            )
            HomeTab.ACCOUNT -> if (accountHistoryOpen) {
                HistoryScreen(state, padding, onLoadMoreHistory)
            } else {
                AccountScreen(state, padding, onLogout, onChangeInstance) {
                    accountHistoryOpen = true
                }
            }
        }
    }
}

@Composable
private fun MobileTopBar(
    tab: HomeTab,
    state: AppUiState,
    conversation: ConversationTarget?,
    accountHistoryOpen: Boolean,
    onRefresh: () -> Unit,
    onBackConversation: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accountHistoryOpen || tab == HomeTab.MESSAGES && conversation != null) {
                    IconButton(onClick = onBackConversation) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = if (accountHistoryOpen) "Back to account" else "All conversations",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)) {
                        Text(
                            "v",
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        when {
                            accountHistoryOpen -> "History"
                            tab == HomeTab.MESSAGES && conversation != null -> conversation.title
                            tab == HomeTab.MESSAGES -> "veejr"
                            else -> tab.title
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        if (accountHistoryOpen) {
                            "Encrypted archive · ${state.account?.handle.orEmpty()}"
                        } else if (tab == HomeTab.MESSAGES && conversation != null) {
                            "Encrypted ${conversation.subjectType} conversation"
                        } else if (tab == HomeTab.MESSAGES) {
                            "Private conversations · ${state.account?.handle.orEmpty()}"
                        } else {
                            state.account?.handle.orEmpty()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                    )
                }
            }
            if (tab == HomeTab.MESSAGES || accountHistoryOpen) {
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Sync history",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationScreen(
    state: AppUiState,
    conversation: ConversationTarget,
    padding: PaddingValues,
    onSend: (String, String, String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val visibleMessages = conversationTimeline(state.messages, conversation)
    val listState = rememberLazyListState()
    val composerIndex = if (visibleMessages.isEmpty()) 2 else visibleMessages.size + 1

    LaunchedEffect(conversation.id, visibleMessages.size, draft) {
        listState.scrollToItem(composerIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading(
                conversation.title,
                "End-to-end encrypted · plaintext stays on this device",
            )
        }

        if (visibleMessages.isEmpty()) {
            item {
                EmptyState(
                    "No messages yet",
                    "Send the first encrypted message to ${conversation.title}.",
                )
            }
        } else {
            items(visibleMessages, key = { it.publicId }) { message ->
                MessageBubble(message, state.account?.handle.orEmpty())
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        minLines = 1,
                        maxLines = 4,
                        enabled = !state.loading,
                        shape = RoundedCornerShape(24.dp),
                    )
                    Button(
                        onClick = {
                            onSend(conversation.subjectType, conversation.id, draft)
                            draft = ""
                        },
                        modifier = Modifier.padding(start = 8.dp).height(56.dp).widthIn(min = 72.dp),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        enabled = draft.isNotBlank() && !state.loading,
                    ) {
                        Text("Send")
                    }
                }
            }
        }

        state.error?.let { error -> item { ErrorBanner(error) } }
    }
}

private enum class HistoryFilter(val title: String, val kind: String?) {
    EVERYTHING("Everything", null),
    MESSAGES("Messages", "message"),
    LOCATIONS("Locations", "location"),
    NOTES("Notes", "note"),
}

@Composable
private fun MessagesScreen(
    state: AppUiState,
    padding: PaddingValues,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    val conversations = buildList {
        state.account?.let { account ->
            add(
                ConversationTarget(
                    "self",
                    account.id,
                    "Notes to yourself",
                    setOf(account.handle),
                    "ME",
                ),
            )
        }
        state.contacts.forEach { contact ->
            add(ConversationTarget("contact", contact.id, contact.handle, setOf(contact.handle)))
        }
        state.groups.forEach { group ->
            add(
                ConversationTarget(
                    "group",
                    group.id,
                    group.name,
                    group.members.map { it.handle }.toSet(),
                ),
            )
        }
    }.sortedWith(
        compareByDescending<ConversationTarget> { it.subjectType == "self" }
            .thenByDescending { target ->
                messagesForConversation(state.messages, target).firstOrNull()?.createdAt.orEmpty()
            },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        if (state.notifications.isNotEmpty()) {
            item {
                SectionHeading(
                    "Message requests",
                    "${state.notifications.size} waiting for your consent",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.notifications, key = { "request-${it.id}" }) { notification ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                    ConsentCard(notification.sender.handle, notification.kind, {
                        onAccept(notification.id)
                    }, {
                        onDecline(notification.id)
                    })
                }
            }
        }

        item {
            SectionHeading(
                "Conversations",
                "Contacts and groups in one place",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (conversations.isEmpty()) {
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    EmptyState("No conversations yet", "Add a trusted contact to start messaging.")
                }
            }
        }
        items(conversations, key = { "${it.subjectType}-${it.id}" }) { target ->
            val latest = messagesForConversation(state.messages, target).firstOrNull()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenConversation(target.subjectType, target.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(target.title, target.initials)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            target.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        latest?.let {
                            Text(
                                it.createdAt.replace('T', ' ').take(16),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        latest?.let { message ->
                            messagePreview(message, state.account?.handle.orEmpty())
                        }
                            ?: if (target.subjectType == "group") "Group · tap to start" else "Tap to start a message",
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        state.error?.let { error -> item { Box(Modifier.padding(16.dp)) { ErrorBanner(error) } } }
    }
}

@Composable
private fun HistoryScreen(
    state: AppUiState,
    padding: PaddingValues,
    onLoadMore: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.EVERYTHING) }
    val items = state.messages.filter { filter.kind == null || it.kind == filter.kind }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(nearEnd, state.historyNextCursor) {
        if (nearEnd && state.historyNextCursor != null && !state.historyLoadingMore) {
            onLoadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading("Your history", "Messages, shared places, and private map notes")
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HistoryFilter.entries, key = { it.name }) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.title) },
                    )
                }
            }
        }
        if (items.isEmpty()) {
            item { EmptyState("Nothing here yet", "Encrypted ${filter.title.lowercase()} will appear here.") }
        } else {
            items(items, key = { it.publicId }) { message ->
                MessageBubble(message, state.account?.handle.orEmpty())
            }
        }
        if (state.historyLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        } else if (state.historyLoaded && state.historyNextCursor == null && items.isNotEmpty()) {
            item {
                Text(
                    "End of encrypted history",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { error -> item { ErrorBanner(error) } }
    }
}

internal fun messagesForConversation(
    messages: List<InboxMessage>,
    conversation: ConversationTarget,
): List<InboxMessage> = messages.filter { message ->
    if (conversation.subjectType == "self") {
        message.sentByMe && message.recipientHandles.toSet() == conversation.memberHandles
    } else if (message.sentByMe) {
        conversation.memberHandles.isNotEmpty() &&
            conversation.memberHandles.all(message.recipientHandles::contains)
    } else {
        message.senderHandle in conversation.memberHandles
    }
}

internal fun conversationTimeline(
    messages: List<InboxMessage>,
    conversation: ConversationTarget,
): List<InboxMessage> = messagesForConversation(messages, conversation).sortedBy(InboxMessage::createdAt)

private fun messagePreview(message: InboxMessage, selfHandle: String): String {
    val content = when (message.kind) {
        "location" -> "📍 ${message.text.ifBlank { "Shared a location" }}"
        "note" -> "📝 ${message.title ?: message.text.ifBlank { "Map note" }}"
        else -> message.text
    }
    return if (message.sentByMe) "${messageDirectionLabel(message, selfHandle)} · $content" else content
}

internal fun messageDirectionLabel(message: InboxMessage, selfHandle: String): String {
    if (!message.sentByMe) return message.senderHandle
    val recipients = message.recipientHandles.filterNot { it == selfHandle }.distinct()
    return when {
        recipients.isNotEmpty() -> "To ${recipients.joinToString()}"
        selfHandle in message.recipientHandles -> "To yourself"
        else -> "Sent"
    }
}

@Composable
private fun ContactsScreen(
    state: AppUiState,
    padding: PaddingValues,
    onSetDeliveryPolicy: (String, String, String?) -> Unit,
    onSavePrivateNote: (String, String, String) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading("Trusted contacts", "Control consent globally or override one conversation")
        }
        if (state.contacts.isEmpty()) {
            item { EmptyState("No contacts yet", "Friend requests and contact management are next on the parity roadmap.") }
        }
        items(state.contacts, key = { "contact-${it.id}" }) { contact ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier.weight(1f).clickable {
                                onOpenConversation("contact", contact.id)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(contact.handle)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(contact.handle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    if (contact.autoAccept) "Effective: Auto accept" else "Effective: Ask first",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        IconButton(onClick = {
                            expandedIds = if (contact.id in expandedIds) {
                                expandedIds - contact.id
                            } else {
                                expandedIds + contact.id
                            }
                        }) {
                            Icon(
                                if (contact.id in expandedIds) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = "Contact settings",
                            )
                        }
                    }
                    if (contact.id in expandedIds) {
                        DeliveryPolicySelector(
                            "Contact default",
                            state.policyAcceptance("contact", contact.id),
                            !state.loading,
                        ) { onSetDeliveryPolicy("contact", contact.id, it) }
                        DeliveryPolicySelector(
                            "Conversation override",
                            state.policyAcceptance("conversation", contact.id),
                            !state.loading,
                        ) { onSetDeliveryPolicy("conversation", contact.id, it) }
                        PrivateNoteEditor(contact.note, !state.loading) {
                            onSavePrivateNote("contact", contact.id, it)
                        }
                    }
                }
            }
        }
        state.error?.let { error -> item { ErrorBanner(error) } }
    }
}

@Composable
private fun GroupsScreen(
    state: AppUiState,
    padding: PaddingValues,
    onSetDeliveryPolicy: (String, String, String?) -> Unit,
    onSavePrivateNote: (String, String, String) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Your groups", "Recipient-owned defaults; the strictest matching group wins") }
        if (state.groups.isEmpty()) {
            item { EmptyState("No groups yet", "Create groups in the server app; native group editing comes next.") }
        }
        items(state.groups, key = { "group-${it.id}" }) { group ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f).clickable {
                                onOpenConversation("group", group.id)
                            },
                        ) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                group.members.joinToString { it.handle }.ifBlank { "No members" },
                                modifier = Modifier.padding(top = 3.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            expandedIds = if (group.id in expandedIds) {
                                expandedIds - group.id
                            } else {
                                expandedIds + group.id
                            }
                        }) {
                            Icon(
                                if (group.id in expandedIds) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = "Group settings",
                            )
                        }
                    }
                    if (group.id in expandedIds) {
                        DeliveryPolicySelector(
                            "Incoming message policy",
                            state.policyAcceptance("group", group.id),
                            !state.loading,
                        ) { onSetDeliveryPolicy("group", group.id, it) }
                        PrivateNoteEditor(group.note, !state.loading) {
                            onSavePrivateNote("group", group.id, it)
                        }
                    }
                }
            }
        }
        state.error?.let { error -> item { ErrorBanner(error) } }
    }
}

@Composable
private fun AccountScreen(
    state: AppUiState,
    padding: PaddingValues,
    onLogout: () -> Unit,
    onChangeInstance: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val account = state.account ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        account.displayName ?: account.username,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(account.handle, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Identity unlocked on this device",
                        modifier = Modifier.padding(top = 18.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        item {
            InfoCard("Instance", state.endpoint.removePrefix("https://"))
        }
        item {
            InfoCard("Encryption", "Portable identity keys configured · plaintext is memory-only")
        }
        item {
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.History, contentDescription = null)
                Text("Open encrypted history", Modifier.padding(start = 8.dp))
            }
        }
        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Sign out") }
        }
        item {
            TextButton(onClick = onChangeInstance, modifier = Modifier.fillMaxWidth()) {
                Text("Forget this instance")
            }
        }
        state.error?.let { error -> item { ErrorBanner(error) } }
    }
}

@Composable
private fun DeliveryPolicySelector(
    label: String,
    selection: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    Text(
        label,
        modifier = Modifier.padding(top = 14.dp),
        style = MaterialTheme.typography.labelLarge,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(null to "Inherit", "ask" to "Ask", "automatic" to "Auto").forEach { (value, title) ->
            FilterChip(
                selected = selection == value,
                onClick = { onSelect(value) },
                label = { Text(title) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PrivateNoteEditor(
    initialBody: String,
    enabled: Boolean,
    onSave: (String) -> Unit,
) {
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    Text(
        "Private note",
        modifier = Modifier.padding(top = 14.dp),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        "Stored on your server · not end-to-end encrypted",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = body,
        onValueChange = { body = it },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        placeholder = { Text("Add context only you can see…") },
        minLines = 2,
        maxLines = 4,
        enabled = enabled,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = { onSave(body) },
            enabled = enabled && body != initialBody,
        ) {
            Text("Save note")
        }
    }
}

private fun AppUiState.policyAcceptance(subjectType: String, subjectId: String): String? =
    deliveryPolicies.firstOrNull {
        it.subjectType == subjectType && it.subjectId == subjectId
    }?.acceptance

@Composable
private fun SectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsentCard(
    handle: String,
    kind: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF7E0),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("$handle sent an encrypted $kind", fontWeight = FontWeight.Bold)
            Text(
                "Content remains unavailable until you consent.",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDecline) { Text("Decline") }
                Button(onClick = onAccept) { Text("Accept") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: InboxMessage, selfHandle: String) {
    val mine = message.sentByMe
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 330.dp),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (mine) 20.dp else 5.dp,
                bottomEnd = if (mine) 5.dp else 20.dp,
            ),
            color = if (mine) Color(0xFFD9FDD3) else MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                Text(
                    messageDirectionLabel(message, selfHandle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                if (message.kind != "message") {
                    Text(
                        if (message.kind == "location") "📍 Location" else "📝 Note",
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                message.title?.takeIf(String::isNotBlank)?.let { title ->
                    Text(title, modifier = Modifier.padding(top = 3.dp), fontWeight = FontWeight.Bold)
                }
                message.text.takeIf(String::isNotBlank)?.let { text ->
                    Text(text, modifier = Modifier.padding(top = 3.dp))
                }
                if (message.kind in setOf("location", "note") &&
                    message.latitude != null && message.longitude != null
                ) {
                    Text(
                        "${"%.5f".format(message.latitude)}, ${"%.5f".format(message.longitude)}",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    message.createdAt.replace('T', ' ').take(16),
                    modifier = Modifier.align(Alignment.End).padding(top = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Avatar(handle: String, initials: String? = null) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(initials ?: handle.trimStart('@').take(1).uppercase(), fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                detail,
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(value, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            error,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
