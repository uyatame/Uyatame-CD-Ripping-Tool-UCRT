package com.uyatame.ucrt

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.player.ExternalMedia
import kotlinx.coroutines.delay

/**
 * ほかの音楽アプリ(ストリーミングサービスなど)で再生中の曲。ライブラリの上に出し、タップで操作画面を開く
 */
@Composable
fun ExternalNowPlayingCard(modifier: Modifier = Modifier) {
    val n = ExternalMedia.now ?: return
    var open by remember { mutableStateOf(false) }
    if (open) ExternalPlayerSheet { open = false }
    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ExternalArt(n, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    S(R.string.playing_in_app, n.appName), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.artist.isNotEmpty()) {
                    Text(
                        n.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (n.canPrev) IconButton({ ExternalMedia.previous() }) { Icon(AppIcons.SkipPrevious, S(R.string.previous_track)) }
            IconButton({ ExternalMedia.playPause() }) {
                Icon(if (n.playing) AppIcons.Pause else Icons.Filled.PlayArrow, if (n.playing) S(R.string.pause) else S(R.string.play))
            }
            if (n.canNext) IconButton({ ExternalMedia.next() }) { Icon(AppIcons.SkipNext, S(R.string.next_track)) }
        }
    }
}

@Composable
private fun ExternalArt(n: ExternalMedia.NowPlaying, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val art = n.art
        if (art != null) Image(art, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else Icon(AppIcons.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** ほかのアプリの曲を操作する画面(ジャケット・曲名・再生位置・前後の曲) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalPlayerSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val n = ExternalMedia.now
        if (n == null) {
            // 再生していたアプリが終了した
            LaunchedEffect(Unit) { onDismiss() }
            return@ModalBottomSheet
        }
        // 再生位置は自分で進める(アプリからの知らせは、再生・一時停止・移動のときにしか来ない)
        var pos by remember { mutableLongStateOf(n.positionNow()) }
        var seeking by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(n) {
            while (true) {
                pos = n.positionNow()
                delay(500)
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                S(R.string.playing_in_app, n.appName), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            ExternalArt(n, Modifier.widthIn(max = 280.dp).fillMaxWidth(0.7f).aspectRatio(1f))
            Spacer(Modifier.height(4.dp))
            Text(n.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(n.artist, n.album).filter { it.isNotEmpty() }.joinToString(S(R.string.separator))
            if (sub.isNotEmpty()) {
                Text(
                    sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (n.durationMs > 0) {
                val frac = seeking ?: (pos.toFloat() / n.durationMs).coerceIn(0f, 1f)
                Slider(
                    value = frac,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { ExternalMedia.seekTo((it * n.durationMs).toLong()) }
                        seeking = null
                    },
                    enabled = n.canSeek,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtMs((frac * n.durationMs).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(fmtMs(n.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton({ ExternalMedia.previous() }, enabled = n.canPrev, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipPrevious, S(R.string.previous_track), Modifier.size(32.dp))
                }
                FilledIconButton({ ExternalMedia.playPause() }, Modifier.size(72.dp)) {
                    Icon(
                        if (n.playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                        if (n.playing) S(R.string.pause) else S(R.string.play), Modifier.size(40.dp),
                    )
                }
                IconButton({ ExternalMedia.next() }, enabled = n.canNext, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipNext, S(R.string.next_track), Modifier.size(32.dp))
                }
            }
            FilledTonalButton({ ExternalMedia.openApp(ctx) }) { Text(S(R.string.open_app, n.appName)) }
            Text(
                S(R.string.external_audio_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
