package com.naoufel.decentralchess

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naoufel.decentralchess.p2p.*\nimport java.util.UUID

private enum class CommunityTab { CHANNELS, DMS, TOURNAMENTS, GAMES }

@Composable
fun CommunityScreen(repository: CommunityRepository, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(CommunityTab.CHANNELS) }
    var selectedChannel by remember { mutableStateOf<CommunityChannel?>(null) }
    var selectedPeer by remember { mutableStateOf<PeerProfile?>(null) }
    var showChallenge by remember { mutableStateOf(false) }
    var showModeration by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val history = repository

    val channels = remember(repository) {
        listOf(
            CommunityChannel("global-chess", "Global Chess", CommunityScope.GLOBAL, "Open discussion, puzzles and games", 1248),
            CommunityChannel("rapid", "Rapid Arena", CommunityScope.GLOBAL, "10+0, 10+5 and blitz practice", 386),
            CommunityChannel("local-be", "Belgium • Local", CommunityScope.LOCAL, "Nearby players and meetups", 74),
            CommunityChannel("beginners", "Beginners", CommunityScope.GLOBAL, "Learn together from the basics", 219)
        )
    }
    val peers = remember(repository) {
        listOf(
            PeerProfile("peer-alice", "AliceKnight", "demo", 1842, 312),
            PeerProfile("peer-bob", "BobbyRook", "demo", 1610, 188),
            PeerProfile("peer-coach", "CoachBot", "demo", 2200, 999, bot = true)
        )
    }
    val tournaments = remember(repository) {
        listOf(
            TournamentRoom("t1", "Friday Rapid Cup", "peer-alice", 32, true),
            TournamentRoom("t2", "Open Casual Arena", "peer-bob", 64, false),
            TournamentRoom("t3", "Beginner Ladder", "peer-coach", 16, false)
        )
    }

    if (selectedChannel != null) {
        CommunityChannelChat(
            channel = selectedChannel!!,
            history = history,
            draft = draft,
            onDraftChange = { draft = it },
            onBack = { selectedChannel = null },
            onPeerClick = { id -> selectedPeer = peers.firstOrNull { p -> p.peerId == id } },
            onChallenge = { id -> selectedPeer = peers.firstOrNull { p -> p.peerId == id }; showChallenge = selectedPeer != null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Community", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                    actions = { Text("P2P", modifier = Modifier.padding(end = 16.dp), color = MaterialTheme.colorScheme.primary) }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    CommunityTab.values().forEach { item ->
                        Tab(selected = tab == item, onClick = { tab = item }, text = {
                            Text(when(item) {
                                CommunityTab.CHANNELS -> "Channels"
                                CommunityTab.DMS -> "DMs"
                                CommunityTab.TOURNAMENTS -> "Events"
                                CommunityTab.GAMES -> "Games"
                            })
                        })
                    }
                }
                when (tab) {
                    CommunityTab.CHANNELS -> ChannelList(channels) { selectedChannel = it }
                    CommunityTab.DMS -> DirectMessageList(peers) { selectedPeer = it }
                    CommunityTab.TOURNAMENTS -> TournamentList(tournaments) { room ->\n                        selectedPeer = peers.firstOrNull { it.peerId == room.ownerPeerId }\n                        showChallenge = selectedPeer != null\n                    }
                    CommunityTab.GAMES -> ActiveGames(peers) { selectedPeer = it; showChallenge = true }
                }
            }
        }
    }

    selectedPeer?.let { peer ->
        PeerProfileDialog(peer, { selectedPeer = null }, { showChallenge = true }, { showModeration = true })
    }
    if (showChallenge && selectedPeer != null) ChallengeDialog(selectedPeer!!, repository) { showChallenge = false }
    if (showModeration && selectedPeer != null) ModerationDialog(selectedPeer!!, repository) { showModeration = false }
}

@Composable
private fun ChannelList(channels: List<CommunityChannel>, onClick: (CommunityChannel) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionHeader("Discover") }
        items(channels) { channel ->
            Card(Modifier.fillMaxWidth().clickable { onClick(channel) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("# ${channel.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        AssistChip(onClick = {}, label = { Text(channel.scope.name.lowercase()) })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(channel.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("${channel.memberCount} members", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DirectMessageList(peers: List<PeerProfile>, onClick: (PeerProfile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionHeader("Direct messages") }
        items(peers) { peer ->
            ListItem(
                headlineContent = { Text(peer.displayName, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(if (peer.bot) "AI coach • ready to help" else "Peer ${peer.rating} • ${peer.gamesPlayed} games") },
                leadingContent = { Text(if (peer.bot) "🤖" else "♟", style = MaterialTheme.typography.headlineSmall) },
                modifier = Modifier.clickable { onClick(peer) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TournamentList(tournaments: List<TournamentRoom>, onJoin: (TournamentRoom) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionHeader("Tournament rooms") }
        items(tournaments) { room ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(room.name, fontWeight = FontWeight.Bold)
                    Text("${if (room.rated) "Rated" else "Casual"} • up to ${room.maxPlayers} players")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onJoin(room) }) { Text("Join room") }
                }
            }
        }
    }
}

@Composable
private fun ActiveGames(peers: List<PeerProfile>, onSpectate: (PeerProfile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionHeader("Live games & spectators") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("AliceKnight  •  BobbyRook", fontWeight = FontWeight.Bold)
                    Text("Rapid 10+0  •  move 27  •  42 spectators")
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onSpectate(peers[0]) }) { Text("Open game chat") }
                }
            }
        }
    }
}

@Composable
private fun CommunityChannelChat(
    channel: CommunityChannel,
    history: CommunityHistory,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onPeerClick: (String) -> Unit,
    onChallenge: (String) -> Unit
) {
    var messages by remember(channel.id) {
        mutableStateOf(
            history.messages(channel.id).ifEmpty {
                listOf(
                    CommunityMessage("welcome", channel.id, "peer-alice", "Welcome to ${channel.name}! ♟", System.currentTimeMillis()),
                    CommunityMessage("tip", channel.id, "peer-coach", "Ask me for a puzzle or position analysis.", System.currentTimeMillis())
                )
            }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("# ${channel.name}"); Text("${channel.memberCount} members", style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, onDraftChange, Modifier.weight(1f), placeholder = { Text("Message…") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(enabled = draft.isNotBlank(), onClick = {
                    val msg = CommunityMessage("local-${System.nanoTime()}", channel.id, "me", draft.trim(), System.currentTimeMillis())
                    history.append(msg)
                    messages = messages + msg
                    onDraftChange("")
                }) { Text("Send") }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                Card(Modifier.fillMaxWidth().clickable { if (msg.senderPeerId != "me") onPeerClick(msg.senderPeerId) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(msg.senderPeerId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(msg.text)
                        if (msg.senderPeerId != "me") TextButton(onClick = { onChallenge(msg.senderPeerId) }) { Text("Challenge") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerProfileDialog(peer: PeerProfile, onDismiss: () -> Unit, onChallenge: () -> Unit, onModerate: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(peer.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (peer.bot) "AI Coach" else "Community peer")
                Text("Rating: ${peer.rating}")
                Text("Games: ${peer.gamesPlayed}")
                Text("Identity: ${peer.publicKeyBase64.take(12)}…", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { Button(onClick = onChallenge) { Text("Challenge") } },
        dismissButton = { TextButton(onClick = onModerate) { Text("Moderate") } }
    )
}

@Composable
private fun ChallengeDialog(peer: PeerProfile, repository: CommunityRepository, onDismiss: () -> Unit) {
    var control by remember { mutableStateOf("10+0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Challenge ${peer.displayName}") },
        text = {
            Column {
                Text("Choose a time control")
                listOf("3+0", "5+0", "10+0", "15+10").forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { control = option }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(control == option, { control = option })
                        Text(option)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = {\n            repository.saveChallenge(CommunityChallenge(UUID.randomUUID().toString(), "me", peer.peerId, control, "startpos", System.currentTimeMillis()))\n            onDismiss()\n        }) { Text("Send ${control}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ModerationDialog(peer: PeerProfile, repository: CommunityRepository, onDismiss: () -> Unit) {
    var blocked by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moderate ${peer.displayName}") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(blocked, { blocked = it })
                    Text("Block peer")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(muted, { muted = it })
                    Text("Mute peer")
                }
                TextButton(onClick = onDismiss) { Text("Report user") }
            }
        },
        confirmButton = { Button(onClick = { repository.setBlocked(peer.peerId, blocked); repository.setMuted(peer.peerId, muted); onDismiss() }) { Text("Save") } }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
}
