package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberTransparentLogo(resId: Int): ImageBitmap {
    val context = LocalContext.current
    return remember(resId) {
        try {
            val original = BitmapFactory.decodeResource(context.resources, resId)
            val width = original.width
            val height = original.height
            val mutable = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(mutable)
            canvas.drawBitmap(original, 0f, 0f, null)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = mutable.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    if (r > 210 && g > 210 && b > 210) {
                        mutable.setPixel(x, y, Color.TRANSPARENT)
                    }
                }
            }
            if (original != mutable) {
                original.recycle()
            }
            mutable.asImageBitmap()
        } catch (e: Exception) {
            BitmapFactory.decodeResource(context.resources, resId).asImageBitmap()
        }
    }
}
