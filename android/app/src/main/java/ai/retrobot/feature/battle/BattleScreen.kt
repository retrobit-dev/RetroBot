package ai.retrobot.feature.battle

import ai.retrobot.data.ModelEntity
import ai.retrobot.di.AppContainer
import ai.retrobot.ui.components.MessageText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The arena screen: two models, one prompt, one blind vote.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BattleScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    viewModel: BattleViewModel = viewModel(
        factory = BattleViewModel.factory(
            repository = container.repository,
            battleEngine = container.battleEngine,
            settings = container.settingsStore,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickingSlot by remember { mutableStateOf<Slot?>(null) }
    var showTuning by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Arena", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (state.totalVotes == 0) {
                                "no votes yet"
                            } else {
                                "${state.totalVotes} votes cast"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTuning = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Battle settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // ---- model picks --------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelChip(
                    label = "Model A",
                    model = state.slotA,
                    modifier = Modifier.weight(1f),
                    onClick = { pickingSlot = Slot.A },
                )
                ModelChip(
                    label = "Model B",
                    model = state.slotB,
                    modifier = Modifier.weight(1f),
                    onClick = { pickingSlot = Slot.B },
                )
            }

            // ---- prompt -------------------------------------------------
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChange,
                label = { Text("Prompt") },
                placeholder = { Text("Ask both models the same question…") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            // ---- actions ------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRunning) {
                    FilledTonalButton(onClick = viewModel::stop) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = viewModel::start,
                        enabled = state.canStart,
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Battle")
                    }
                }

                if (state.hasVoted) {
                    TextButton(onClick = viewModel::reset) { Text("New battle") }
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // ---- the two panes -----------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResponsePane(
                    title = "Response A",
                    subtitle = state.revealed ? state.paneLeft?.displayName : null,
                    text = state.leftText,
                    error = state.leftError,
                    isStreaming = state.isRunning,
                    costUsd = state.leftCostUsd,
                    modifier = Modifier.weight(1f),
                )
                ResponsePane(
                    title = "Response B",
                    subtitle = state.revealed ? state.paneRight?.displayName : null,
                    text = state.rightText,
                    error = state.rightError,
                    isStreaming = state.isRunning,
                    costUsd = state.rightCostUsd,
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- the vote ----------------------------------------------
            if (!state.isRunning && (state.leftText.isNotBlank() || state.rightText.isNotBlank())) {
                VoteBar(
                    hasVoted = state.hasVoted,
                    revealed = state.revealed,
                    onVoteLeft = { viewModel.vote("left") },
                    onVoteRight = { viewModel.vote("right") },
                    onTie = { viewModel.vote("tie") },
                )
            }
        }
    }

    pickingSlot?.let { slot ->
        ModelPickerSheet(
            models = state.models,
            selectedKey = when (slot) {
                Slot.A -> state.slotA?.key
                Slot.B -> state.slotB?.key
            },
            title = when (slot) {
                Slot.A -> "Choose model A"
                Slot.B -> "Choose model B"
            },
            onDismiss = { pickingSlot = null },
            onSelect = { model -> viewModel.selectModel(slot, model) },
        )
    }

    if (showTuning) {
        BattleTuningDialog(
            temperature = state.temperature,
            maxTokens = state.maxTokens,
            systemPrompt = state.systemPrompt,
            onTemperatureChange = viewModel::onTemperatureChange,
            onMaxTokensChange = viewModel::onMaxTokensChange,
            onSystemPromptChange = viewModel::onSystemPromptChange,
            onDismiss = { showTuning = false },
        )
    }
}

@Composable
private fun BattleTuningDialog(
    temperature: Double,
    maxTokens: Int,
    systemPrompt: String,
    onTemperatureChange: (Double) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battle settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Temperature · ${"%.2f".format(temperature)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = temperature.toFloat(),
                    onValueChange = { onTemperatureChange(it.toDouble()) },
                    valueRange = 0f..1.5f,
                    steps = 14,
                )

                Text(
                    text = "Max tokens · $maxTokens",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { onMaxTokensChange(it.toInt()) },
                    valueRange = 256f..8192f,
                    steps = 30,
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text("System prompt") },
                    supportingText = { Text("Sent to both models, so the comparison stays fair.") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ModelChip(
    label: String,
    model: ModelEntity?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = model?.displayName ?: "Pick a model",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResponsePane(
    title: String,
    subtitle: String?,
    text: String,
    error: String?,
    isStreaming: Boolean,
    costUsd: Double?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    text.isEmpty() && isStreaming -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )

                    text.isEmpty() -> Text(
                        text = "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> MessageText(content = text)
                }
            }

            if (costUsd != null && costUsd > 0.0) {
                Text(
                    text = "$${"%.4f".format(costUsd)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoteBar(
    hasVoted: Boolean,
    revealed: Boolean,
    onVoteLeft: () -> Unit,
    onVoteRight: () -> Unit,
    onTie: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasVoted) {
            Text(
                text = "Which answer is better?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (revealed) "Vote recorded — identities revealed" else "Vote recorded",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onVoteLeft,
                enabled = !hasVoted,
                modifier = Modifier.weight(1f),
            ) {
                Text("◀ A is better", textAlign = TextAlign.Center)
            }
            OutlinedButton(
                onClick = onTie,
                enabled = !hasVoted,
            ) {
                Text("Tie")
            }
            FilledTonalButton(
                onClick = onVoteRight,
                enabled = !hasVoted,
                modifier = Modifier.weight(1f),
            ) {
                Text("B is better ▶", textAlign = TextAlign.Center)
            }
        }
    }
}
