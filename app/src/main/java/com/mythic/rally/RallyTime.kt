package com.mythic.rally

import java.util.Locale

object RallyTime {
    fun digits(raw: String): String = raw.filter { it in '0'..'9' }.take(4)
    fun preview(raw: String): String {
        val d=digits(raw)
        if(d.length<=2)return d
        val minutes=d.dropLast(2).trimStart('0').ifEmpty { "0" }
        return minutes+":"+d.takeLast(2)
    }
    fun seconds(raw: String): Int? {
        val d=digits(raw);if(d.isEmpty())return null
        val minutes=if(d.length>2)d.dropLast(2).toIntOrNull()?:return null else 0
        val secs=if(d.length>2)d.takeLast(2).toIntOrNull()?:return null else d.toIntOrNull()?:return null
        val total=minutes*60+secs
        return total.takeIf { it<=3600 }
    }
    fun format(total: Int): String = String.format(Locale.US,"%d:%02d",total/60,total%60)
    fun normalize(raw: String): String? = seconds(raw)?.let(::format)
}