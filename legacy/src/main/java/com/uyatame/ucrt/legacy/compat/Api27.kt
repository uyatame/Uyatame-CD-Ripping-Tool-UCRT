package com.uyatame.ucrt.legacy.compat

/** Android 8.1 からの API(呼ぶ前に版を確かめる) */
object Api27 {
    /** システムのロックの上に出す(false で外す) */
    fun showWhenLocked(a: android.app.Activity, on: Boolean = true) = a.setShowWhenLocked(on)
}

/** Android 4.4W(API 20)からの API */
object Api20 {
    /** 画面が点いているか */
    fun interactive(pm: android.os.PowerManager): Boolean = pm.isInteractive
}

/** Android 10 からの API */
object Api29 {
    /** ホームアプリの役割を求める(システムの「ホームアプリにしますか」を出す)。出せなかったら false */
    fun requestHomeRole(a: android.app.Activity): Boolean {
        val rm = a.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) || rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) return false
        @Suppress("DEPRECATION")
        a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 4201)
        return true
    }
}
