package com.example.ytlocal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

private val PALETTE = intArrayOf(
    0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
    0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFDCDFE4.toInt(),
    0xFF7F848E.toInt(), 0xFFFF7B86.toInt(), 0xFFB5E890.toInt(), 0xFFFFD98C.toInt(),
    0xFF7CC4FF.toInt(), 0xFFE08FFF.toInt(), 0xFF70D6E3.toInt(), 0xFFFFFFFF.toInt()
)

private fun color256(n: Int): Int = when {
    n < 16 -> PALETTE[n.coerceAtLeast(0)]
    n < 232 -> {
        val v = n - 16
        fun lvl(x: Int) = if (x == 0) 0 else 55 + 40 * x
        (0xFF shl 24) or (lvl(v / 36) shl 16) or (lvl(v / 6 % 6) shl 8) or lvl(v % 6)
    }
    else -> {
        val g = (8 + 10 * (n - 232)).coerceAtMost(255)
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
}

private data class Sty(val fg: Int = 0, val bold: Boolean = false, val ul: Boolean = false)

private class Line {
    val chars = StringBuilder()
    val styles = ArrayList<Sty>()
    var cache: AnnotatedString? = null

    fun put(col: Int, ch: Char, st: Sty) {
        while (chars.length < col) { chars.append(' '); styles.add(Sty()) }
        if (col < chars.length) { chars.setCharAt(col, ch); styles[col] = st }
        else { chars.append(ch); styles.add(st) }
        cache = null
    }

    fun truncate(col: Int) {
        if (col < chars.length) {
            chars.setLength(col)
            while (styles.size > col) styles.removeAt(styles.size - 1)
            cache = null
        }
    }

    fun annotated(): AnnotatedString = cache ?: build().also { cache = it }

    private fun build(): AnnotatedString {
        if (chars.isEmpty()) return AnnotatedString(" ")
        return buildAnnotatedString {
            var i = 0
            while (i < chars.length) {
                val st = styles[i]
                var j = i
                while (j < chars.length && styles[j] == st) j++
                val span = SpanStyle(
                    color = if (st.fg != 0) Color(st.fg) else Color.Unspecified,
                    fontWeight = if (st.bold) FontWeight.Bold else null,
                    textDecoration = if (st.ul) TextDecoration.Underline else null
                )
                withStyle(span) { append(chars.substring(i, j)) }
                i = j
            }
        }
    }
}

/** Emulador de terminal minimo: texto, \r, \n, SGR (colores), borrado de pantalla/linea. */
class TermBuffer(private val maxLines: Int = 1500) {
    private val lines = ArrayList<Line>().also { it.add(Line()) }
    private var col = 0
    private var st = Sty()
    private var state = 0
    private val param = StringBuilder()

    @Synchronized
    fun append(s: String) { for (ch in s) feed(ch) }

    @Synchronized
    fun snapshot(): List<AnnotatedString> = lines.map { it.annotated() }

    private fun feed(ch: Char) {
        when (state) {
            0 -> when (ch) {
                '\u001b' -> state = 1
                '\n' -> newLine()
                '\r' -> col = 0
                '\b' -> if (col > 0) col--
                '\t' -> repeat(8 - col % 8) { put(' ') }
                else -> if (ch >= ' ') put(ch)
            }
            1 -> when (ch) {
                '[' -> { state = 2; param.setLength(0) }
                ']' -> state = 3
                else -> state = 0
            }
            2 -> if (ch.isDigit() || ch == ';' || ch == '?') param.append(ch) else { csi(ch); state = 0 }
            3 -> when (ch) {
                '\u0007' -> state = 0
                '\u001b' -> state = 4
                else -> {}
            }
            else -> state = 0
        }
    }

    private fun put(ch: Char) {
        lines.last().put(col, ch, st)
        col++
    }

    private fun newLine() {
        lines.add(Line())
        col = 0
        while (lines.size > maxLines) lines.removeAt(0)
    }

    private fun csi(final: Char) {
        when (final) {
            'm' -> sgr()
            'J' -> {
                val p = param.toString()
                if (p == "2" || p == "3") { lines.clear(); lines.add(Line()); col = 0 }
            }
            'K' -> lines.last().truncate(if (param.toString() == "2") 0 else col)
            else -> {}
        }
    }

    private fun sgr() {
        val p = if (param.isEmpty()) listOf(0) else param.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < p.size) {
            val n = p[i]
            when {
                n == 0 -> st = Sty()
                n == 1 -> st = st.copy(bold = true)
                n == 4 -> st = st.copy(ul = true)
                n == 22 -> st = st.copy(bold = false)
                n == 24 -> st = st.copy(ul = false)
                n in 30..37 -> st = st.copy(fg = PALETTE[n - 30])
                n == 39 -> st = st.copy(fg = 0)
                n in 90..97 -> st = st.copy(fg = PALETTE[n - 90 + 8])
                n == 38 && i + 2 < p.size && p[i + 1] == 5 -> { st = st.copy(fg = color256(p[i + 2])); i += 2 }
                n == 38 && i + 4 < p.size && p[i + 1] == 2 -> {
                    st = st.copy(fg = (0xFF shl 24) or (p[i + 2] shl 16) or (p[i + 3] shl 8) or p[i + 4]); i += 4
                }
            }
            i++
        }
    }
}
