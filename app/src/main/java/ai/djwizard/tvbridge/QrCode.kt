package ai.djwizard.tvbridge

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// Tiny ZXing wrapper that turns a string into a monochrome Bitmap suitable
// for an ImageView. The TV-side pair screen uses this to encode the /claim
// URL so a phone can just scan it instead of typing the 6-digit code.
//
// Kept stdlib-light on purpose: only zxing:core, no android-embedded layer.
object QrCode {

    // Rendered at the full requested size. Error-correction level M is the
    // comfortable default — survives a phone camera at a few feet but doesn't
    // bloat the matrix. Margin of 1 module avoids the oversized quiet zone
    // ZXing would otherwise pick on its own.
    fun render(text: String, sizePx: Int): Bitmap {
        require(sizePx > 0) { "sizePx must be positive" }

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        // Per-row setPixels is ~10x faster than one setPixel per module for
        // the sizes we render here (300-400px). Pre-allocating the row buffer
        // keeps allocation out of the inner loop.
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val row = IntArray(sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                row[x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
            bmp.setPixels(row, 0, sizePx, 0, y, sizePx, 1)
        }
        return bmp
    }
}
