package com.slothspeak.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slothspeak.data.LlmProviderCatalog
import com.slothspeak.data.LlmProviderUiState
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.TextSecondary

/**
 * Compact pill showing the active model's friendly name with a chevron.
 * Rendered directly below the home-screen composer; tapping opens the
 * [ModelSwitcherSheet].
 */
@Composable
fun ModelSwitcherPill(
    activeProvider: LlmProviderUiState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = LlmProviderCatalog.friendlyModelName(activeProvider?.modelId ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = AccentGreen
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Switch model",
                tint = AccentGreen,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Bottom sheet hosting the model switcher. Providers are folded by default;
 * multi-model providers expand to reveal per-model rows. Kept separate from
 * [ModelSwitcherContent] so screenshot tests can render the content directly
 * inside a plain Surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSwitcherSheet(
    providers: List<LlmProviderUiState>,
    onSelectModel: (LlmProviderUiState, String) -> Unit,
    onManageModels: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        ModelSwitcherContent(
            providers = providers,
            onSelectModel = onSelectModel,
            onManageModels = onManageModels,
            onDismiss = onDismiss
        )
    }
}

/**
 * Folded-by-default provider list with expandable per-model rows.
 *
 * - Tapping a folded provider row activates it with its current model.
 * - Providers exposing more than one model show an expand chevron; expanding
 *   reveals indented rows for each available model. Tapping a model row
 *   selects it.
 * - The active provider carries a check badge.
 */
@Composable
fun ModelSwitcherContent(
    providers: List<LlmProviderUiState>,
    onSelectModel: (LlmProviderUiState, String) -> Unit,
    onManageModels: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    var expandedKeys by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        ) {
            providers.forEachIndexed { index, provider ->
                val expanded = provider.key in expandedKeys
                val hasSubModels = provider.availableModels.size > 1

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!hasSubModels || expanded) {
                                // Folded single-model rows activate directly;
                                // expanded headers also behave as "use current".
                                onSelectModel(provider, provider.modelId)
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = LlmProviderCatalog.friendlyModelName(provider.modelId),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    if (provider.active) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Active",
                            tint = AccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (hasSubModels) {
                        IconButton(onClick = { toggleExpanded(provider.key, expandedKeys) { next -> expandedKeys = next } }) {
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) "Collapse models" else "Expand models",
                                tint = AccentGreen
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, bottom = 4.dp)
                    ) {
                        provider.availableModels.forEach { modelId ->
                            val isCurrentModel = provider.active && provider.modelId == modelId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectModel(provider, modelId)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                // Checkmark only marks the active model
                                if (isCurrentModel) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = AccentGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                } else {
                                    Spacer(modifier = Modifier.width(26.dp))
                                }
                                Text(
                                    text = LlmProviderCatalog.friendlyModelName(modelId),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrentModel) AccentGreen else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (index < providers.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        TextButton(
            onClick = onManageModels,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp)
        ) {
            Text("Manage models in Settings", color = AccentGreen)
        }
    }
}

private fun toggleExpanded(key: String, current: Set<String>, update: (Set<String>) -> Unit) {
    update(if (key in current) current - key else current + key)
}
