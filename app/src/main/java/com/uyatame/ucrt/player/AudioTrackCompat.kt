package com.uyatame.ucrt.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioFormat as AFormat

// AudioTrack.Builder(Android 6.0 から)を使う処理は、このファイルだけに置く
// (再生の処理と同じファイルに置くと、Android 5.x でそのクラスの検証に失敗して遅くなることがあるため)

/**
 * ストリーム再生用の AudioTrack を作る。Builder は Android 6.0 からなので、5.x は同じ内容のコンストラクタで作る
 * (作れなかったときは、Builder と同じく UnsupportedOperationException)
 */
internal fun newAudioTrack(attrs: AudioAttributes, format: AFormat, bufferBytes: Int): AudioTrack {
    if (android.os.Build.VERSION.SDK_INT >= 23) {
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
    val t = AudioTrack(attrs, format, bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
    if (t.state != AudioTrack.STATE_INITIALIZED) {
        t.release()
        throw UnsupportedOperationException("cannot initialize AudioTrack")
    }
    return t
}
