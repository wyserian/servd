package dev.servd.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.servd.core.qr.Qr

/** Draws core's QR grid as a [Bitmap] for the control screen (an ImageView can't draw core's SVG). */
object QrBitmap {
    fun of(text: String, sizePx: Int): Bitmap {
        val matrix = Qr.matrix(text, sizePx)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }
}
