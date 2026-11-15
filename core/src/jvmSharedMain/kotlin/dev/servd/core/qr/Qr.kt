package dev.servd.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for servd's links: one encoder setup, drawn as SVG here and as a bitmap on Android. */
object Qr {
    /** The QR grid for [text], scaled to about [size] px (0 = one pixel per module), with a 2-module quiet zone. */
    fun matrix(text: String, size: Int = 0): BitMatrix {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    }

    /** [text] as a self-contained SVG string (no rasterization, works offline). */
    fun svg(text: String): String {
        val matrix = matrix(text)
        val w = matrix.width
        val h = matrix.height
        val path = StringBuilder()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (matrix.get(x, y)) path.append('M').append(x).append(' ').append(y).append("h1v1h-1z")
            }
        }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h)
            append("\" shape-rendering=\"crispEdges\">")
            append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" fill=\"#ffffff\"/>")
            append("<path fill=\"#000000\" d=\"").append(path).append("\"/>")
            append("</svg>")
        }
    }
}
