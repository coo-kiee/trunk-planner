package ai.cargo.feature.pairing

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** The QR symbol is black on white for scanners; this is a machine code and takes no theme colors. */
object QrEncoder {
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1

    fun encode(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(sizePx * sizePx) { i -> if (matrix.get(i % sizePx, i / sizePx)) BLACK else WHITE }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
