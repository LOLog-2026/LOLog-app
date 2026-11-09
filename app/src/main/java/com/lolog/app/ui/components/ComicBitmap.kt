package com.lolog.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** 목업 만화를 PNG로 저장·공유할 때 쓰는 그림 (실제 서버에서는 imageUrl을 받아요) */
object ComicBitmap {
    private val bgs = intArrayOf(0xFFFFF4DB.toInt(), 0xFFE7ECFF.toInt(), 0xFFE1F5F1.toInt(), 0xFFFFE9E6.toInt())

    /** 세로 4칸 한 장 (컷마다 정사각형) */
    fun render(lines: List<String>, width: Int = 1080): Bitmap {
        val gap = 12f
        val cell = width.toFloat()
        val bmp = Bitmap.createBitmap(width, (cell * 4 + gap * 3).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFF191F28.toInt() }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 44f; color = 0xFF191F28.toInt() }
        for (i in 0 until 4) {
            val top = i * (cell + gap)
            fill.color = bgs[i]
            c.drawRect(0f, top, cell, top + cell, fill)
            // 사람
            val cx = cell / 2
            c.drawCircle(cx, top + cell * 0.62f, cell * 0.09f, stroke)
            c.drawArc(RectF(cx - cell * 0.18f, top + cell * 0.74f, cx + cell * 0.18f, top + cell * 1.1f), 180f, 180f, false, stroke)
            // 말풍선
            val line = lines.getOrNull(i) ?: continue
            val w = (cell * 0.7f).toInt()
            val layout = StaticLayout.Builder.obtain(line, 0, line.length, text, w - 48)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).build()
            val bx = if (i % 2 == 0) 28f else cell - w - 28f
            val by = top + 28
            fill.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(RectF(bx, by, bx + w, by + layout.height + 36), 36f, 36f, fill)
            c.save(); c.translate(bx + 24, by + 18); layout.draw(c); c.restore()
        }
        return bmp
    }
}
