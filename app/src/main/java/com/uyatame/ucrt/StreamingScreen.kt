package com.uyatame.ucrt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.player.ExternalMedia
import com.uyatame.ucrt.spotify.SpotifyViewModel

/** ストリーミングのタブの番号(設定のタブ 2 の番号は、前の版から変えない) */
const val TAB_STREAMING = 3

/** Spotify アプリのパッケージ名 */
private const val SPOTIFY_PACKAGE = "com.spotify.music"

/**
 * ストリーミングのタブ。
 * - ほかの音楽アプリ(Spotify・Apple Music・Amazon Music など)で再生中の曲の表示と操作(「通知へのアクセス」の許可で、どのアプリでも)
 * - サービスごとの機能(今は Spotify。オンにすると、Spotify のライブラリを探して公式 Spotify アプリで再生できる)
 *
 * spotifyOpen: Spotify の画面を開いているか(再生画面から Spotify のページへ移るときなどに、外から開くため App が持つ)
 */
@Composable
fun StreamingScreen(
    vm: MainViewModel,
    sp: SpotifyViewModel,
    s: AppSettings,
    spotifyOpen: Boolean,
    onSpotifyOpen: (Boolean) -> Unit,
    onOpenSpotifyPlayer: () -> Unit,
) {
    if (spotifyOpen && s.spotifyEnabled) {
        // Spotify の画面の中のページを戻りきったら、ストリーミングのトップへ
        BackHandler(enabled = sp.stack.isEmpty()) { onSpotifyOpen(false) }
        SpotifyScreen(sp, onBack = { sp.home(); onSpotifyOpen(false) }) { if (vm.player.isPlaying) vm.player.pause() }
        return
    }
    WideCenter {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp + barInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(S(R.string.streaming))
            // ---- 再生中(ほかの音楽アプリ) ----
            val n = ExternalMedia.now
            if (n != null) {
                // Spotify の機能を使っていて Spotify アプリにつながっていれば、Spotify の再生画面(次の曲・機器の切り替えなど)を開く
                val spotify = n.packageName == SPOTIFY_PACKAGE && s.spotifyEnabled && sp.connected && sp.hasTrack
                ExternalNowPlayingCard(vm, onOpen = if (spotify) onOpenSpotifyPlayer else null)
            } else {
                Text(
                    if (s.externalMedia && ExternalMedia.permitted) S(R.string.streaming_nothing_playing)
                    else S(R.string.streaming_allow_to_show),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ---- サービスごとの機能 ----
            Section(S(R.string.streaming_services)) {
                ServiceRow(
                    "Spotify", S(R.string.streaming_spotify_desc),
                    enabled = s.spotifyEnabled,
                    onToggle = { on ->
                        vm.set(Keys.spotifyEnabled, on)
                        if (!on) { sp.disconnect(); onSpotifyOpen(false) }
                    },
                    onOpen = { onSpotifyOpen(true) },
                )
                Text(
                    S(R.string.streaming_spotify_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ---- ほかの音楽アプリの表示・操作(通知へのアクセス) ----
            ExternalMediaSection(vm, s)
        }
    }
}

/** サービスの行: 名前・説明・使うかのスイッチ。使っていれば、行のタップでそのサービスの画面を開く */
@Composable
private fun ServiceRow(title: String, desc: String, enabled: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Cloud, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) S(R.string.streaming_tap_to_open) else desc,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(enabled, onToggle)
            if (enabled) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}
