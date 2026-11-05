package com.example.audiobookplayer.util

import java.util.*

/**
 * 自然排序比较器，正确处理数字序号：
 * 第1章、第2章...第10章，而不是字典序的1,10,2
 * 支持中文数字、阿拉伯数字混合排序
 */
class NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        var nza: Int
        var nzb: Int
        var ca: Char
        var cb: Char
        var result: Int

        while (ia < a.length && ib < b.length) {
            ca = a[ia]
            cb = b[ib]

            // 跳过空白
            while (Character.isWhitespace(ca)) {
                ia++
                if (ia >= a.length) break
                ca = a[ia]
            }
            while (Character.isWhitespace(cb)) {
                ib++
                if (ib >= b.length) break
                cb = b[ib]
            }

            // 数字块比较
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                nza = ia
                nzb = ib
                while (ia < a.length && Character.isDigit(a[ia])) ia++
                while (ib < b.length && Character.isDigit(b[ib])) ib++
                result = a.substring(nza, ia).toLong().compareTo(b.substring(nzb, ib).toLong())
                if (result != 0) return result
            } else {
                // 普通字符比较，同时处理中文数字
                val numA = chineseNumberValue(ca)
                val numB = chineseNumberValue(cb)
                result = if (numA != null && numB != null) {
                    numA.compareTo(numB)
                } else {
                    ca.lowercaseChar().compareTo(cb.lowercaseChar())
                }
                if (result != 0) return result
                ia++
                ib++
            }
        }
        return a.length - b.length
    }

    private fun chineseNumberValue(c: Char): Int? {
        return when (c) {
            '零', '〇' -> 0
            '一', '壹' -> 1
            '二', '两', '贰' -> 2
            '三', '叁' -> 3
            '四', '肆' -> 4
            '五', '伍' -> 5
            '六', '陆' -> 6
            '七', '柒' -> 7
            '八', '捌' -> 8
            '九', '玖' -> 9
            '十', '拾' -> 10
            '百', '佰' -> 100
            '千', '仟' -> 1000
            '万' -> 10000
            else -> null
        }
    }

    companion object {
        val INSTANCE = NaturalOrderComparator()
    }
}
