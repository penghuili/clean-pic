package com.screensweep.util

import android.content.Context
import android.text.format.Formatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatSize(context: Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes)

/** 无 Context 时的粗略大小展示 */
fun approximateSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** 今天 / 昨天 / M月d日（跨年补年份） */
fun dayLabel(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val dNow = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
    val dThen = then.get(Calendar.YEAR) * 1000 + then.get(Calendar.DAY_OF_YEAR)
    return when (dNow - dThen) {
        0 -> "今天"
        1 -> "昨天"
        else -> {
            val s = SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ms))
            if (then.get(Calendar.YEAR) != now.get(Calendar.YEAR))
                "${then.get(Calendar.YEAR)}年$s" else s
        }
    }
}

fun formatDateTime(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    return if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
    } else {
        SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ms))
    }
}
