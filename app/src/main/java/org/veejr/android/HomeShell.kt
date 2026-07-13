package org.veejr.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import kotlinx.coroutines.delay

private enum class HomeTab(val title: String, val icon: ImageVector) {
    INBOX("Inbox", Icons.AutoMirrored.Outlined.Chat),
    CONTACTS("Contacts", Icons.Outlined.People),
    GROUPS("Groups", Icons.Outlined.Groups),
    ACCOUNT("Account", Icons.Outlined.Settings),
}

@Composable
fun HomeScreen(
    state: AppUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onSend: (String, String) -> Unit,
    onSetDeliveryPolicy: (String, String, String?) -> Unit,
    onLogout: () -> Unit,
    onChangeInstance: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.INBOX) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            onSync()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MobileTopBar(tab, state, onRefresh) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
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
        },
    ) { padding ->
        when (tab) {
            HomeTab.INBOX -> InboxScreen(state, padding, onAccept, onDecline, onSend)
            HomeTab.CONTACTS -> ContactsScreen(state, padding, onSetDeliveryPolicy)
            HomeTab.GROUPS -> GroupsScreen(state, padding, onSetDeliveryPolicy)
            HomeTab.ACCOUNT -> AccountScreen(state, padding, onLogout, onChangeInstance)
        }
    }
}

@Composable
private fun MobileTopBar(tab: HomeTab, state: AppUiState, onRefresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)) {
                    Text(
                        "v",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Black,
                    )
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        if (tab == HomeTab.INBOX) "veejr" else tab.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        if (tab == HomeTab.INBOX) "Inbox · ${state.account?.handle.orEmpty()}" else state.account?.handle.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                    )
                }
            }
            if (tab == HomeTab.INBOX) {
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Sync inbox",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxScreen(
    state: AppUiState,
    padding: PaddingValues,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onSend: (String, String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedContactId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedContact = state.contacts.firstOrNull { it.id == selectedContactId }
        ?: state.contacts.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.notifications.isNotEmpty()) {
            item {
                SectionHeading(
                    "Requests",
                    "${state.notifications.size} encrypted message${if (state.notifications.size == 1) "" else "s"} waiting",
                )
            }
            items(state.notifications, key = { "request-${it.id}" }) { notification ->
                ConsentCard(notification.sender.handle, notification.kind, {
                    onAccept(notification.id)
                }, {
                    onDecline(notification.id)
                })
            }
        }

        item {
            SectionHeading("Messages", "End-to-end encrypted · plaintext stays on this device")
        }

        if (state.messages.isEmpty()) {
            item { EmptyState("No messages yet", "Choose a contact below and start a private conversation.") }
        } else {
            items(state.messages, key = { it.publicId }) { message -> MessageBubble(message) }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("New message", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (state.contacts.isEmpty()) {
                        Text(
                            "Add an accepted friend in the server app to begin.",
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.contacts.take(3).forEach { contact ->
                                FilterChip(
                                    selected = selectedContact?.id == contact.id,
                                    onClick = { selectedContactId = contact.id },
                                    label = {
                                        Text(contact.handle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            placeholder = { Text("Write an encrypted message…") },
                            minLines = 2,
                            maxLines = 5,
                            enabled = !state.loading,
                        )
                        Button(
                            onClick = {
                                selectedContact?.let { onSend(it.id, draft) }
                                draft = ""
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
                            enabled = selectedContact != null && draft.isNotBlank() && !state.loading,
                        ) {
                            Text("Send to ${selectedContact?.handle.orEmpty()}")
                        }
                    }
                }
            }
        }

        state.error?.let { error -> item { ErrorBanner(error) } }
    }
}

@Composable
private fun ContactsScreen(
    state: AppUiState,
    padding: PaddingValues,
    onSetDeliveryPolicy: (String, String, String?) -> Unit,
) {
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
) {
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
                    Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        group.members.joinToString { it.handle }.ifBlank { "No members" },
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DeliveryPolicySelector(
                        "Incoming message policy",
                        state.policyAcceptance("group", group.id),
                        !state.loading,
                    ) { onSetDeliveryPolicy("group", group.id, it) }
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

private fun AppUiState.policyAcceptance(subjectType: String, subjectId: String): String? =
    deliveryPolicies.firstOrNull {
        it.subjectType == subjectType && it.subjectId == subjectId
    }?.acceptance

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column {
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
private fun MessageBubble(message: InboxMessage) {
    val mine = message.senderHandle == "You"
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
                    message.senderHandle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(message.text, modifier = Modifier.padding(top = 3.dp))
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
private fun Avatar(handle: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(handle.trimStart('@').take(1).uppercase(), fontWeight = FontWeight.Black)
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
