package com.uyatame.ucrt.player

/** アプリ内蔵のデコーダーの一覧(拡張子で選ぶ) */
internal object BuiltinCodecs {
    /** ファイル名から、内蔵のデコーダーの種類("flac" など)。内蔵で扱わない形式なら null */
    fun kindOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "flac" -> "flac"
        "aif", "aiff", "aifc" -> "aiff"
        // m4a / mp4 は中身が ALAC のときだけ内蔵で読む(AAC・MPEG-H なら open() が例外を出し、OS のデコーダーに任せる)
        "m4a", "mp4" -> "alac"
        else -> null
    }

    fun create(kind: String, src: RandomInput): PcmDecoder = when (kind) {
        "flac" -> FlacDecoder(src)
        "aiff" -> AiffDecoder(src)
        "alac" -> AlacDecoder(src)
        else -> throw java.io.IOException("unknown kind $kind")
    }

    /** 曲の情報(一覧・画面の表示用)。読めなければ null */
    class Info(val rate: Int, val channels: Int, val bits: Int, val durationMs: Long)

    /** content:// などのファイルを、位置を指定して読めるようにする(使い終わったら close) */
    class FdInput(private val pfd: android.os.ParcelFileDescriptor) : RandomInput, java.io.Closeable {
        private val fis = java.io.FileInputStream(pfd.fileDescriptor)
        private val ch = fis.channel
        override val size: Long = runCatching { ch.size() }.getOrDefault(pfd.statSize)
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int = ch.read(java.nio.ByteBuffer.wrap(b, off, len), pos)
        override fun close() {
            runCatching { ch.close() }
            runCatching { fis.close() }
            runCatching { pfd.close() }
        }
    }

    fun openInput(ctx: android.content.Context, uri: android.net.Uri): FdInput? =
        runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()?.let { FdInput(it) }

    /** 曲の情報と曲情報(タグ)をまとめて読む。読めなければ null */
    fun probe(ctx: android.content.Context, uri: android.net.Uri, kind: String): Pair<Info, com.uyatame.ucrt.tags.Id3Data?>? {
        val inp = openInput(ctx, uri) ?: return null
        return inp.use { i -> info(kind, i)?.let { it to com.uyatame.ucrt.tags.ExtraTags.read(kind, i) } }
    }

    fun info(kind: String, src: RandomInput): Info? = runCatching {
        val d = create(kind, src)
        d.open()
        Info(d.sampleRate, d.channels, d.bitsPerSample, if (d.sampleRate > 0) d.totalSamples * 1000 / d.sampleRate else 0L)
    }.getOrNull()
}
