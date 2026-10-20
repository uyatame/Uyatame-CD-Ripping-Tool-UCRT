package com.uyatame.ucrt

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.player.ExternalMedia

/**
 * ほかの音楽アプリ(ストリーミングサービスなど)で再生中の曲。ライブラリの上に出し、タップで再生画面を開く
 */
@Composable
fun ExternalNowPlayingCard(vm: MainViewModel, modifier: Modifier = Modifier) {
    val n = ExternalMedia.now ?: return
    Surface(
        onClick = { vm.externalPlayerOpen = true },
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
