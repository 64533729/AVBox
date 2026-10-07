package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.theme.cardContainer

private val WeekdayRes = listOf(
    R.string.weekday_mon,
    R.string.weekday_tue,
    R.string.weekday_wed,
    R.string.weekday_thu,
    R.string.weekday_fri,
    R.string.weekday_sat,
    R.string.weekday_sun,
)

private val ReminderHours = 0..23

private val HourListHeight = 168.dp

@Composable
fun FollowReminderSheet(onDismissRequest: () -> Unit) {
    val dismissAnimated = LocalSheetDismiss.current
    var selectedDays by rememberSaveable { mutableStateOf(setOf(0)) }
    var selectedHour by rememberSaveable { mutableStateOf(21) }
    var selectedMinute by rememberSaveable { mutableStateOf(30) }
    var manualTime by rememberSaveable { mutableStateOf("") }
    var timeExpanded by rememberSaveable { mutableStateOf(false) }

    val separator = stringResource(R.string.follow_days_separator)
    val timeText = formatReminderTime(selectedHour, selectedMinute)
    val dayLabels = WeekdayRes.map { stringResource(it) }
    val daysText = dayLabels
        .filterIndexed { index, _ -> index in selectedDays }
        .joinToString(separator)

    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        headerContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tab_following),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.follow_reminder_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.follow_reminder_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        },
    ) {
        ReminderSectionTitle(
            titleRes = R.string.follow_update_day,
            hintRes = R.string.follow_update_day_hint,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            WeekdayRes.forEachIndexed { index, nameRes ->
                WeekdayChip(
                    label = stringResource(nameRes),
                    selected = index in selectedDays,
                    onClick = {
                        selectedDays = if (index in selectedDays) {
                            if (selectedDays.size > 1) selectedDays - index else selectedDays
                        } else {
                            selectedDays + index
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ReminderSectionTitle(
            titleRes = R.string.follow_reminder_time,
            hintRes = R.string.follow_reminder_time_hint,
        )
        Surface(
            onClick = { timeExpanded = !timeExpanded },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.cardContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (timeExpanded) 90f else 0f),
                )
            }
        }

        if (timeExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.cardContainer)
                    .height(HourListHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                ReminderHours.forEach { hour ->
                    HourRow(
                        label = hour.toString().padStart(2, '0'),
                        selected = hour == selectedHour,
                        onClick = {
                            selectedHour = hour
                            selectedMinute = 0
                            manualTime = ""
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualTime,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it == ':' }.take(5)
                    manualTime = filtered
                    val hour = filtered.substringBefore(':').toIntOrNull()
                    if (hour != null && hour in ReminderHours) {
                        selectedHour = hour
                        filtered.substringAfter(':', "").toIntOrNull()
                            ?.takeIf { it in 0..59 }
                            ?.let { selectedMinute = it }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.follow_schedule_time_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.cardContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.follow_summary, daysText, timeText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Button(
            onClick = { dismissAnimated() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.follow_save_settings))
        }
    }
}

@Composable
private fun ReminderSectionTitle(titleRes: Int, hintRes: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(hintRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekdayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.cardContainer
        },
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun HourRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

private fun formatReminderTime(hour: Int, minute: Int): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
