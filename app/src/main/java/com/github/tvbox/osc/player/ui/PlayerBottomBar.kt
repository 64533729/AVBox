package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.util.PlayerUtils.stringForTime

private const val SEEK_MAX = 1000

private val PreviewPlayPauseBox = 40.dp

@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    actions: PlayerActions,
    iconBox: Dp,
    modifier: Modifier = Modifier,
) {
    if (!state.controlsVisible) return
    val edge = playerEdgePadding()
    val bottomPad = if (state.previewMode) {
        (16.dp + playerDim(R.dimen.vs_30) / 2 - PreviewPlayPauseBox / 2).coerceAtLeast(0.dp)
    } else {
        6.dp
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.5f),
                )
            )
            .padding(start = edge, end = edge, top = 10.dp, bottom = bottomPad)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            if (!state.previewMode) {
                PlayerTimePill(state = state, modifier = Modifier.align(Alignment.CenterStart))
                VideoSizePill(state = state, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = if (state.previewMode) 44.dp else 0.dp),
        ) {
            if (state.previewMode) {
                PreviewPlayPauseButton(state, actions)
            }

            if (state.previewMode) {
                CurrentTimeText(state)
            }
            PlayerSeekRow(
                state = state,
                actions = actions,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = if (state.previewMode) 8.dp else 0.dp),
            )
            if (state.previewMode) {
                Text(
                    text = stringForTime(state.duration),
                    color = Color.White,
                    fontSize = playerTextSize(R.dimen.ts_20),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
        }

        if (!state.previewMode) {
            PlayerActionPill(
                actions = actions,
                iconBox = iconBox,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (state.showParseRow && !state.previewMode) {
            val parseList = remember(state.parseListVersion) { ApiConfig.get().parseBeanList.toList() }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_menu_parse),
                    color = Color.White,
                    fontSize = playerTextSize(R.dimen.ts_20),
                    maxLines = 1,
                    modifier = Modifier.padding(end = playerDim(R.dimen.vs_10)),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                    items(parseList.size) { index ->
                        val item = parseList[index]
                        PlayerMenuButton(
                            item.name.orEmpty(),
                            onClick = { actions.onParseSelected(index) },
                            textColor = if (item.isDefault) Color(0xFF02F8E1) else Color.White,
                            textSizeId = R.dimen.ts_20,
                        )
                    }
                }
            }
        }
    }
}

internal const val OVERLAY_PILL_ALPHA = 0.2f

private const val PILL_DIVIDER_ALPHA = 0.3f

@Composable
private fun PlayerActionPill(
    actions: PlayerActions,
    iconBox: Dp,
    modifier: Modifier = Modifier,
) {
    val gap = playerDim(R.dimen.vs_8)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_refresh,
            label = stringResource(R.string.common_refresh),
            box = iconBox,
            onClick = actions::onRefreshClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.ic_detail_cast,
            label = stringResource(R.string.common_cast),
            box = iconBox,
            onClick = actions::onCastClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_subtitle,
            label = stringResource(R.string.player_menu_subtitle),
            box = iconBox,
            onClick = actions::onSubtitleClicked,
            onLongClick = actions::onSubtitleLongClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_danmu,
            label = stringResource(R.string.player_menu_danmu),
            box = iconBox,
            onClick = actions::onDanmuSettingClicked,
            onLongClick = actions::onDanmuSettingLongClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_audio,
            label = stringResource(R.string.player_menu_audio_track),
            box = iconBox,
            onClick = actions::onAudioTrackClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_video,
            label = stringResource(R.string.player_menu_video_track),
            box = iconBox,
            onClick = actions::onVideoTrackClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillDivider(iconBox)
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_episodes,
            label = stringResource(R.string.detail_episodes),
            box = iconBox,
            onClick = actions::onEpisodeClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_params,
            label = stringResource(R.string.player_menu_more),
            box = iconBox,
            onClick = actions::onParamsClicked,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerPillDivider(iconBox: Dp) {
    Box(
        Modifier
            .padding(horizontal = playerDim(R.dimen.vs_8))
            .width(1.dp)
            .height(iconBox * ICON_TO_BOX_RATIO)
            .background(Color.White.copy(alpha = PILL_DIVIDER_ALPHA), RoundedCornerShape(50)),
    )
}

@Composable
private fun PlayerInfoPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = OVERLAY_PILL_ALPHA), RoundedCornerShape(50))
            .padding(
                horizontal = playerDim(R.dimen.vs_10),
                vertical = playerDim(R.dimen.vs_5),
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PlayerTimePill(state: PlayerUiState, modifier: Modifier = Modifier) {
    PlayerInfoPill(modifier) { TimeRangeText(state) }
}

@Composable
private fun VideoSizePill(state: PlayerUiState, modifier: Modifier = Modifier) {
    if (state.videoSize.isBlank()) return
    PlayerInfoPill(modifier) {
        Text(
            text = state.videoSize,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimeRangeText(state: PlayerUiState) {
    Text(
        text = stringForTime(state.seekPreviewOrPosition) + " / " + stringForTime(state.duration),
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
private fun CurrentTimeText(state: PlayerUiState, modifier: Modifier = Modifier) {
    Text(
        text = stringForTime(state.seekPreviewOrPosition),
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = modifier.widthIn(min = 48.dp),
    )
}

@Composable
private fun PreviewPlayPauseButton(state: PlayerUiState, actions: PlayerActions) {
    val playing = state.playbackActive
    Box(
        modifier = Modifier
            .size(PreviewPlayPauseBox)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { actions.onPlayPauseClicked() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play
            ),
            contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PlayerSeekRow(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier,
) {
    var draggingLocal by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    val thumbActive = draggingLocal || state.dragging

    var seekModifier = modifier
        .height(playerDim(R.dimen.vs_30))
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && state.duration > 0) {
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val dir = when {
                            delta.y != 0f -> if (delta.y > 0) 1 else -1
                            delta.x != 0f -> if (delta.x > 0) 1 else -1
                            else -> continue
                        }
                        actions.onSeekStep(dir)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures { offset ->
                if (state.duration <= 0) return@detectTapGestures
                val target = (offset.x / size.width * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
                actions.onSeekStarted()
                actions.onSeekPreview(target)
                actions.onSeekFinished(target)
            }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    if (state.duration > 0) {
                        actions.onSeekStarted()
                        draggingLocal = true
                        dragProgress = (offset.x / size.width * SEEK_MAX).coerceIn(0f, SEEK_MAX.toFloat())
                        actions.onSeekPreview(dragProgress.toInt())
                    }
                },
                onDragEnd = {
                    if (draggingLocal) {
                        actions.onSeekFinished(dragProgress.toInt())
                    }
                    draggingLocal = false
                },
                onDragCancel = {
                    if (draggingLocal) {
                        actions.onSeekCancelled()
                    }
                    draggingLocal = false
                },
            ) { change, dragAmount ->
                if (draggingLocal) {
                    dragProgress = (dragProgress + dragAmount / size.width * SEEK_MAX)
                        .coerceIn(0f, SEEK_MAX.toFloat())
                    actions.onSeekPreview(dragProgress.toInt())
                    change.consume()
                }
            }
        }

    Canvas(seekModifier) {
        val progress: Float = when {
            state.dragging && state.duration > 0 ->
                state.seekPreviewPositionMs.toFloat() / state.duration * SEEK_MAX
            state.duration > 0 -> state.position.toFloat() / state.duration * SEEK_MAX
            else -> 0f
        }
        val buffered: Float =
            if (state.duration > 0) state.bufferedPercent / 100f * SEEK_MAX else 0f
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(
            color = Color(0x4DFFFFFF),
            topLeft = Offset(0f, centerY - trackHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        if (buffered > 0f) {
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (buffered / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        if (progress > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (progress / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        val thumbRadius = (if (thumbActive) 8.dp else 6.dp).toPx()
        val thumbCenter = Offset(size.width * (progress / SEEK_MAX), centerY)
        drawCircle(Color.White, radius = thumbRadius, center = thumbCenter)
        drawCircle(
            Color.White.copy(alpha = 0.55f),
            radius = thumbRadius,
            center = thumbCenter,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
