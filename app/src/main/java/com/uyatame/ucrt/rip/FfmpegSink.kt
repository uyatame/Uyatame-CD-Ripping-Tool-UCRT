package com.uyatame.ucrt.rip

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue

/**
 * アプリに入れた FFmpeg(lib/<ABI>/libffmpeg.so。tools/ffmpeg/build.sh で作ったもの)。
 * FLAC・MP3 の変換に使う。動かない端末(ファイルが無い・実行できない)では null
 */
object Ffmpeg {
    @Volatile private var checked: File? = null
    @Volatile private var failed = false

    fun binary(ctx: Context, log: (String) -> Unit): File? {
        checked?.let { return it }
        if (failed) return null
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        // 一度だけ、実際に動くかを確かめる(32bit / 64bit の違い・実行の禁止など)
        val ok = f.exists() && runCatching {
            val p = ProcessBuilder(f.path, "-hide_banner", "-version").redirectErrorStream(true).start()
            // 出力は最後まで読む(途中で閉じると、書けなくなった FFmpeg がエラーで終わる)
            val head = p.inputStream.bufferedReader().readText().lineSequence().firstOrNull().orEmpty()
            p.waitFor() == 0 && head.startsWith("ffmpeg").also { if (it) log("ffmpeg: $head") }
        }.getOrElse { e -> log("ffmpeg: cannot run (${e.message})"); false }
        if (ok) checked = f else failed = true
        return checked
    }
}

/**
 * CD の音(16bit/44.1kHz/2ch)を FFmpeg に流し込んで、一時ファイルに変換させる。
 * FFmpeg は別のプロセスで動き、流し込みも別のスレッドで行うので、変換している間も CD の読み取りは止まらない。
 * finish() で変換の終わりを待ち、done(一時ファイル) で保存先に書き出す(曲の情報を付ける)
 */
class FfmpegSink(
    exe: File,
    codecArgs: List<String>,
    private val tmp: File,
    private val done: (File) -> Unit,
) : AudioSink {
    private val proc: Process
    /** 読み取った音の置き場所(約 8 MB まで。CD の読み取りが変換より速いときに貯めておく) */
    private val queue = ArrayBlockingQueue<ByteArray>(128)
    private val writer: Thread
    private val errReader: Thread
    @Volatile private var error: Throwable? = null
    private val errTail = StringBuilder()
    private var finished = false

    init {
        tmp.delete()
        val cmd = listOf(
            exe.path, "-hide_banner", "-nostdin", "-loglevel", "error",
            "-f", "s16le", "-ar", "44100", "-ac", "2", "-i", "pipe:0",
        ) + codecArgs + listOf("-fflags", "+bitexact", "-y", tmp.path)
        proc = ProcessBuilder(cmd).start()
        errReader = Thread({
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { l ->
                    synchronized(errTail) {
                        errTail.append(l).append('\n')
                        if (errTail.length > 2000) errTail.delete(0, errTail.length - 2000)
                    }
                }
            }
        }, "ffmpeg-stderr").apply { isDaemon = true; start() }
        writer = Thread({
            val os = java.io.BufferedOutputStream(proc.outputStream, 1 shl 16)
            try {
                while (true) {
                    val b = queue.take()
                    if (b.isEmpty()) break
                    os.write(b)
                }
                os.close()
            } catch (e: InterruptedException) {
                runCatching { os.close() }
            } catch (e: Throwable) {
                error = e
                // 止まった FFmpeg に書けずに待ち続けないよう、残りは捨てる
                queue.clear()
                runCatching { os.close() }
            }
        }, "ffmpeg-writer").apply { isDaemon = true; start() }
    }

    private fun fail(what: String): IOException {
        val msg = synchronized(errTail) { errTail.toString().trim().lines().lastOrNull().orEmpty() }
        return IOException("FFmpeg: $what" + if (msg.isNotEmpty()) " ($msg)" else "")
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        error?.let { throw fail(it.message ?: it.javaClass.simpleName) }
        queue.put(buf.copyOfRange(off, off + len))
    }

    override fun finish() {
        if (finished) return
        finished = true
        queue.put(ByteArray(0))
        writer.join()
        error?.let { throw fail(it.message ?: it.javaClass.simpleName) }
        val code = proc.waitFor()
        errReader.join(2000)
        if (code != 0) throw fail("exit $code")
        try {
            done(tmp)
        } finally {
            tmp.delete()
        }
    }

    override fun abort() {
        finished = true
        writer.interrupt()
        // waitFor(時間) は Android 8 からなので使わない(destroy で FFmpeg はすぐ止まる)
        proc.destroy()
        tmp.delete()
    }
}
