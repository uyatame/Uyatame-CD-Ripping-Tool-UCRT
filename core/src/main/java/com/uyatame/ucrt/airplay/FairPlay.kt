package com.uyatame.ucrt.airplay

/**
 * FairPlay v3 (SAP) のセットアップとハンドシェイク。GoAirPlay の fairplay.go の移植。
 * 4 つの 142 バイトのセットアップ応答は、オープンソースの AirPlay 受信実装で
 * 公開されているプロトコル定数で、クライアントの 16 バイトのセットアップ
 * メッセージの 14 バイト目で選ばれる。ハンドシェイク応答は固定 12 バイト
 * ヘッダー + 要求の最後の 20 バイトのエコー。保存した 164 バイトの鍵
 * メッセージは、SETUP のときにストリーム鍵の復号 (Playfair) に使う。
 */
internal object FairPlay {

    private val replyMessages = arrayOf(
        "46504c590301020000000082020 00f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba",
        "46504c590301020000000082020 1cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a",
        "46504c590301020000000082020 2c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce",
        "46504c590301020000000082020 39001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359",
    )

    private val header = byteArrayOf(0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x14)

    private val replies: Array<ByteArray> = Array(replyMessages.size) { i ->
        val clean = replyMessages[i].replace(" ", "")
        val b = ByteArray(clean.length / 2) { j ->
            ((clean[2 * j].digitToInt(16) shl 4) or clean[2 * j + 1].digitToInt(16)).toByte()
        }
        require(b.size == 142) { "fp reply $i has ${b.size} bytes, want 142" }
        b
    }

    /**
     * POST /fp-setup を 1 セッション分処理する。
     * req は 16 バイト (セットアップ) または 164 バイト (ハンドシェイク)。
     * 応答ボディを返し、プロトコルエラー時は null。
     */
    fun handle(session: AirPlaySession, req: ByteArray): ByteArray? {
        when (req.size) {
            16 -> {
                if (req[4] != 0x03.toByte()) return null
                val mode = req[14].toInt() and 0xFF
                if (mode >= 4) return null
                session.fpVersion = 3
                return replies[mode]
            }
            164 -> {
                if (req[4] != 0x03.toByte() || (req[12].toInt() and 0xFF) >= 4) return null
                session.fpKeymsg = req.copyOf()
                session.fpVersion = 3
                return header + req.copyOfRange(144, 164)
            }
            else -> return null
        }
    }
}
