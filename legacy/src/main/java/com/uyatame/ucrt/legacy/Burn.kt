package com.uyatame.ucrt.legacy

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.CheckView
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.RadioView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.chip
import com.uyatame.ucrt.legacy.ui.chipRow
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.library.Cue
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.rip.BurnImage
import com.uyatame.ucrt.rip.BurnOptions
import com.uyatame.ucrt.rip.BurnPrep
import com.uyatame.ucrt.rip.BurnSource
import com.uyatame.ucrt.rip.CdBurner
import com.uyatame.ucrt.rip.RipOptions
import com.uyatame.ucrt.usb.ScsiException
import java.io.File

/**
 * CD の書き込み(本家の BurnDialog・BurnTracksDialog と、MainViewModel の書き込みの部分)。
 * CD のコピー(読み取ってから空の CD-R に書く)と、ライブラリの曲から CD を作る
 */
object Burn {
    private val app get() = LegacyApp.app
    private val p get() = app.prefs
    private val main = Handler(Looper.getMainLooper())

    class Session(
        val title: String,
        val image: BurnImage,
        /** 0 = ディスク待ち、1 = 書き込み済み */
        val stage: Int,
        val message: String = "",
        val error: Boolean = false,
        val canErase: Boolean = false,
    ) {
        fun msg(m: String, error: Boolean, canErase: Boolean = false) = Session(title, image, stage, m, error, canErase)
    }

    /** 書き込む内容ができていれば、その書き込み(ディスクの自動検知を止める) */
    @Volatile var session: Session? = null; private set

    private val burnFile: File get() = File(app.cacheDir, "burn.pcm")

    private fun snack(s: String) = main.post { LegacyActivity.current?.snack(s) }

    private fun setSession(s: Session?) {
        session = s
        main.post { Bus.emit(Bus.DISC); dialog() }
    }

    private fun spaceOk(sectors: Long): Boolean {
        val need = sectors * 2352 + (50L shl 20)
        val free = app.cacheDir.usableSpace + (if (burnFile.exists()) burnFile.length() else 0L)
        if (free >= need) return true
        snack(S(R.string.not_enough_free_storage_about).format(need shr 20))
        return false
    }

    private fun rip() = RipOptions(p.speedX, p.sectorsPerRead, p.retries, p.useC2, p.abortOnError, p.offsetSamples)

    // ================= CD のコピー =================

    fun startCopy(act: LegacyActivity) {
        val d = Disc.driveOrNull ?: return
        if (Disc.busy || Disc.discBusy) return
        // 先頭から続いている音楽トラック(エンハンスド CD のデータトラックは除く)
        val tr = Disc.tracks.takeWhile { it.isAudio }
        if (tr.isEmpty()) { act.snack(S(R.string.insert_audio_cd)); return }
        if (!spaceOk((tr.last().endLba - tr.first().startLba).toLong())) return
        if (Disc.playingCd()) app.player.stop()
        val title = Disc.albumTitle
        val lo = Disc.leadOut
        Disc.beginBusy(1, 1)
        Thread({
            try {
                val img = BurnPrep.fromCd(d, tr, lo, rip(), burnFile, { pr -> Disc.setProgress(pr, 0) }) { Disc.check() }
                AppLog.log(S(R.string.read_for_copy_tracks_min, title, tr.size, "%.1f".format(img.seconds / 60)))
                runCatching { d.eject() }
                Disc.afterEject()
                setSession(Session(title, img, 0))
            } catch (e: Canceled) {
                snack(S(R.string.copy_canceled))
                runCatching { burnFile.delete() }
            } catch (e: Exception) {
                snack(S(R.string.could_not_read_cd, e.message))
                AppLog.log(S(R.string.copy_read_error, e.message))
                runCatching { burnFile.delete() }
            } finally {
                Disc.endBusy()
            }
        }, "ucrt-copy").start()
    }

    // ================= ライブラリの曲から =================

    /** 曲間の長さを選んでから、曲を CD の形にする */
    fun request(act: LegacyActivity, title: String, tracks: List<LibTrack>) {
        if (tracks.isEmpty()) return
        if (Disc.busy) { act.snack(S(R.string.legacy_busy)); return }
        var gap = 2
        val totalSec = tracks.sumOf { (if (it.endMs > 0) it.endMs - it.startMs else it.durationMs) } / 1000
        val body = vbox(act)
        body.add(text(act, title, Type.TitleSmall, Pal.onSurface, lines = 2), b = 4)
        body.add(text(act, S(R.string.tracks_about_min, tracks.size, (totalSec + 59) / 60), Type.BodySmall, Pal.onSurfaceVariant), b = 8)
        if (totalSec > 80 * 60) body.add(text(act, S(R.string.too_long_for_one_cd), Type.BodyMedium, Pal.error, lines = 0), b = 8)
        body.add(text(act, S(R.string.gap_between_tracks), Type.LabelLarge, Pal.onSurface), b = 4)
        val radios = ArrayList<Pair<Int, RadioView>>()
        listOf(2 to S(R.string.n2_seconds_standard_for_cds), 0 to S(R.string.none_for_continuous_tracks_e)).forEach { (v, label) ->
            val r = hbox(act)
            val rv = RadioView(act, gap == v)
            radios += v to rv
            r.add(rv, w = 48, h = 48)
            r.add(text(act, label, Type.BodyMedium, Pal.onSurface, lines = 0), w = 0, weight = 1f)
            r.tap(8f) { gap = v; radios.forEach { (x, y) -> y.checked = x == v } }
            body.add(r)
        }
        body.add(text(act, S(R.string.tracks_are_converted_to_cd), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), t = 4)
        val d = UDialog(act).title(S(R.string.write_to_cd)).view(body)
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(S(R.string.prepare), enabled = totalSec <= 80 * 60) { d.dismiss(); startFromTracks(title, tracks, gap) }
        d.show()
    }

    private fun startFromTracks(title: String, list: List<LibTrack>, gapSec: Int) {
        if (Disc.busy || Disc.discBusy || list.isEmpty()) return
        val secs = list.sumOf { (if (it.endMs > 0) it.endMs - it.startMs else it.durationMs) } / 1000 + gapSec * list.size
        if (secs > 80 * 60) { snack(S(R.string.too_long_for_one_cd)); return }
        if (!spaceOk(secs * 75 + 75 * 60)) return
        val src = list.map { BurnSource(Uri.parse(Cue.fileUri(it.uri)), it.startMs, it.endMs) }
        // 古い端末の OS のデコーダーは当てにならないので、アプリ内蔵のデコーダーを先に使う
        BurnPrep.builtinFirst = true
        Disc.beginBusy(2, list.size)
        Thread({
            try {
                val img = BurnPrep.fromFiles(app, src, gapSec * 75, burnFile, { AppLog.log(it) }, { pr ->
                    Disc.setProgress(pr, (pr * list.size).toInt().coerceAtMost(list.size - 1))
                }) { Disc.check() }
                AppLog.log(S(R.string.prepared_for_writing_tracks_min, title, list.size, "%.1f".format(img.seconds / 60)))
                setSession(Session(title, img, 0))
            } catch (e: Canceled) {
                snack(S(R.string.preparation_canceled))
                runCatching { burnFile.delete() }
            } catch (e: Exception) {
                snack(S(R.string.could_not_prepare_tracks, e.message))
                AppLog.log(S(R.string.prepare_error, e.message))
                runCatching { burnFile.delete() }
            } finally {
                Disc.endBusy()
            }
        }, "ucrt-burn-prep").start()
    }

    // ================= 書き込み・消去 =================

    private fun write(speedX: Int, testWrite: Boolean) {
        val b = session ?: return
        val d = Disc.driveOrNull
        if (d == null) { setSession(b.msg(S(R.string.connect_drive), true)); return }
        if (Disc.busy) return
        if (Disc.playingCd()) app.player.stop()
        val burner = CdBurner(d, BurnOptions(speedX, testWrite)) { AppLog.log(it) }
        // ディスクを確かめているあいだも busy にして、二重に書き込まないようにする
        Disc.beginBusy(3, 1)
        Thread({
            var started = false
            try {
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 30_000) {
                    Disc.check()
                    val e = runCatching { d.testUnitReady() }.exceptionOrNull() as? ScsiException ?: break
                    if (!(e.senseKey == 6 || (e.senseKey == 2 && e.asc == 0x04))) break
                    Thread.sleep(500)
                }
                val err = runCatching { burner.checkDisc(b.image); null }.getOrElse { it }
                if (err != null) {
                    val erasable = runCatching { d.readDiscInfo() }.getOrNull()?.let { it.erasable && it.status != 0 } == true
                    setSession(b.msg(if (err is ScsiException && err.asc == 0x3A) S(R.string.no_disc_inserted) else err.message ?: "", true, erasable))
                    return@Thread
                }
                started = true
                Disc.setProgress(0f)
                burner.burn(b.image, { pr -> Disc.setProgress(pr) }) { Disc.check() }
                AppLog.log(S(R.string.written, b.title) + if (testWrite) S(R.string.test_write_2) else "")
                runCatching { d.eject() }
                setSession(Session(b.title, b.image, 1, if (testWrite) S(R.string.test_write_complete_disc_can) else S(R.string.finished_writing_cd)))
            } catch (e: Canceled) {
                setSession(if (started) b.msg(S(R.string.writing_stopped_this_disc_may), true) else b)
            } catch (e: Exception) {
                AppLog.log(S(R.string.write_error, e.message))
                setSession(b.msg(S(R.string.writing_failed, e.message), true))
            } finally {
                Disc.endBusy()
                Disc.resetMedia()
            }
        }, "ucrt-burn").start()
    }

    private fun erase() {
        val b = session ?: return
        val d = Disc.driveOrNull ?: return
        if (Disc.busy) return
        Disc.beginBusy(4, 1)
        Thread({
            try {
                d.blank(minimal = true)
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 30 * 60_000) {
                    Disc.check()
                    val ok = runCatching { d.testUnitReady() }.isSuccess
                    if (ok && runCatching { d.readDiscInfo().status == 0 }.getOrDefault(false)) break
                    Thread.sleep(1000)
                }
                setSession(Session(b.title, b.image, 0, S(R.string.erased_tap_write)))
            } catch (e: Canceled) {
                setSession(b.msg(S(R.string.stopped_waiting_for_erase_drive), true))
            } catch (e: Exception) {
                setSession(b.msg(S(R.string.could_not_erase, e.message), true))
            } finally {
                Disc.endBusy()
            }
        }, "ucrt-erase").start()
    }

    private fun close() {
        if (Disc.busy && Disc.busyKind >= 3) return
        session = null
        runCatching { burnFile.delete() }
        Disc.resetMedia()
        Bus.emit(Bus.DISC)
    }

    // ================= 画面(本家の BurnDialog) =================

    private var shown: UDialog? = null
    private var shownAct: LegacyActivity? = null
    private var unsub: (() -> Unit)? = null
    private var speed = 8
    private var test = false

    /** 書き込みの画面を出す(出ていれば中身を作り直す) */
    fun dialog() {
        val b = session
        val act = LegacyActivity.current
        if (b == null || act == null) { shown?.dismiss(); return }
        val writing = Disc.busy && (Disc.busyKind == 3 || Disc.busyKind == 4)
        val d = shown?.takeIf { shownAct === act } ?: run {
            shown?.dismiss()
            shownAct = act
            UDialog(act).also { nd ->
                nd.setCancelable(false)
                nd.setOnDismissListener { if (shown === nd) { shown = null; unsub?.invoke(); unsub = null } }
                shown = nd
                unsub = Bus.on(Bus.RIP or Bus.DISC) { refresh() }
            }
        }
        fill(act, d, b, writing)
        if (!d.isShowing) d.show()
    }

    private var lastSig = ""
    private var progressBar: ProgressBarView? = null
    private var progressText: android.widget.TextView? = null

    private fun refresh() {
        val b = session ?: run { shown?.dismiss(); return }
        val writing = Disc.busy && (Disc.busyKind == 3 || Disc.busyKind == 4)
        val sig = "${b.hashCode()}/$writing/${Disc.busyKind}"
        if (sig == lastSig) {
            progressBar?.progress = Disc.ripOverall
            progressText?.text = if (Disc.busyKind == 4) S(R.string.erasing) else S(R.string.writing, (Disc.ripOverall * 100).toInt())
            return
        }
        dialog()
    }

    private fun fill(act: LegacyActivity, d: UDialog, b: Session, writing: Boolean) {
        lastSig = "${b.hashCode()}/$writing/${Disc.busyKind}"
        d.clear()
        d.title(if (b.stage == 1) S(R.string.write_complete) else S(R.string.write_to_cd))
        val col = vbox(act)
        val img = b.image
        col.add(text(act, b.title, Type.TitleSmall, Pal.onSurface, lines = 2), b = 4)
        col.add(text(act, S(R.string.tracks_3, img.tracks.size, (img.seconds / 60).toInt(), "%02d".format((img.seconds % 60).toInt())), Type.BodySmall, Pal.onSurfaceVariant), b = 8)
        progressBar = null; progressText = null
        if (writing) {
            val t = text(act, if (Disc.busyKind == 4) S(R.string.erasing) else S(R.string.writing, (Disc.ripOverall * 100).toInt()), Type.BodyMedium)
            col.add(t, b = 6)
            val pb = ProgressBarView(act, Disc.ripOverall, 4)
            col.add(pb, b = 6)
            col.add(text(act, S(R.string.dont_disconnect_drive_or_let), Type.BodySmall, Pal.error, lines = 0))
            progressBar = pb; progressText = t
        } else if (b.stage == 0) {
            col.add(text(act, S(R.string.insert_blank_cd_r_or), Type.BodyMedium, Pal.onSurface, lines = 0), b = 8)
            col.add(text(act, S(R.string.write_speed), Type.LabelLarge), b = 4)
            val chips = ArrayList<View>()
            listOf(4, 8, 16, 0).forEach { x -> chips += chip(act, if (x == 0) S(R.string.max) else "${x}x", speed == x) { speed = x; fill(act, d, b, false) } }
            col.add(chipRow(act, chips), b = 4)
            col.add(text(act, S(R.string.slower_speeds_are_more_reliable), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 4)
            val r = hbox(act)
            val cv = CheckView(act, test) { test = it }
            r.add(cv, w = 48, h = 48)
            val c = vbox(act)
            c.add(text(act, S(R.string.test_write), Type.BodyLarge))
            c.add(text(act, S(R.string.simulates_entire_write_without_burning), Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
            r.add(c, w = 0, weight = 1f)
            r.tap(8f) { test = !test; cv.checked = test }
            col.add(r)
        }
        if (b.message.isNotEmpty()) col.add(text(act, b.message, Type.BodyMedium, if (b.error) Pal.error else Pal.primary, lines = 0), t = 8)
        d.view(col)
        when {
            writing -> d.button(S(R.string.stop)) { Disc.cancelRip() }
            else -> {
                d.button(S(R.string.close)) {
                    if (b.stage == 1) { close(); d.dismiss() } else confirmClose(act, d)
                }
                if (b.stage == 1) d.button(S(R.string.write_another)) { setSession(Session(b.title, b.image, 0, S(R.string.insert_blank_cd_r_and))) }
                else {
                    if (b.canErase) d.button(S(R.string.erase)) { erase() }
                    d.button(S(R.string.write)) { write(speed, test) }
                }
            }
        }
    }

    private fun confirmClose(act: LegacyActivity, parent: UDialog) {
        val c = UDialog(act).title(S(R.string.cancel_writing)).message(S(R.string.prepared_data_will_be_deleted))
        c.button(S(R.string.back)) { c.dismiss() }
        c.button(S(R.string.cancel_writing_2)) { c.dismiss(); close(); parent.dismiss() }
        c.show()
    }
}
