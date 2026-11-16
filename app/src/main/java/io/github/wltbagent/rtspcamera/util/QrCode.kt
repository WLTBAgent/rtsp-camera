package io.github.wltbagent.rtspcamera.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** QR bitmap generation for the connect-info dialog. */
object QrCode {

    fun generate(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) Color.BLACK else Color.WHITE,
                )
            }
        }
        return bitmap
    }
}
