package com.uyatame.ucrt.player

/** アプリ内蔵のデコーダーの一覧(拡張子で選ぶ) */
internal object BuiltinCodecs {
    /** ファイル名から、内蔵のデコーダーの種類("flac" など)。内蔵で扱わない形式なら null */
    fun kindOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "flac" -> "flac"
        "aif", "aiff", "aifc" -> "aiff"
        // m4a / mp4 は中身が ALAC のときだけ内蔵で読む(AAC・MPEG-H なら open() が例外を出し、OS のデコーダーに任せる)
        "m4a", "mp4" -> "alac"
        "wv" -> "wavpack"
        "ape" -> "ape"
        else -> null
    }

    /**
     * ファイルの中身(先頭のバイト)から、内蔵のデコーダーの種類を調べる。
     * 表示名に拡張子が無い・違うファイル(ほかのアプリが保存したものなど)でも内蔵で読めるように。分からなければ null
     */
    fun sniff(src: RandomInput): String? {
        val b = ByteArray(12)
        var n = 0
        while (n < b.size) {
            val r = src.read(n.toLong(), b, n, b.size - n)
            if (r <= 0) break
            n += r
        }
        if (n < 12) return null
        fun at(o: Int, s: String) = s.indices.all { b[o + it] == s[it].code.toByte() }
        return when {
            at(0, "fLaC") -> "flac"
            // ID3 が先頭に付いた FLAC は、FlacDecoder が ID3 を飛ばして確かめる(MP3 なら open() が例外を出す)
            at(0, "ID3") -> "flac"
            at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "aiff"
            at(0, "wvpk") -> "wavpack"
            at(0, "MAC ") -> "ape"
            // MP4 / M4A: 中身が ALAC のときだけ内蔵で読む(AAC などなら open() が例外を出す)
            at(4, "ftyp") -> "alac"
            else -> null
        }
    }

    fun create(kind: String, src: RandomInput): PcmDecoder = when (kind) {
        "flac" -> FlacDecoder(src)
        "aiff" -> AiffDecoder(src)
        "alac" -> AlacDecoder(src)
        "wavpack" -> WavPackDecoder(src)
        "ape" -> ApeDecoder(src)
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
