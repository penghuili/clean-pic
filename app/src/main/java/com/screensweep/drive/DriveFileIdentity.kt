package com.screensweep.drive

import com.screensweep.data.ShotItem
import java.security.MessageDigest

/** Drive 中图片的稳定身份；避免不同目录中的同名图片互相覆盖或误删。 */
object DriveFileIdentity {
    fun keyFor(item: ShotItem): String = keyFor(item.sourceKey, item.uri.toString())

    internal fun keyFor(sourceKey: String, uri: String): String {
        val value = "$sourceKey\u0000$uri".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
