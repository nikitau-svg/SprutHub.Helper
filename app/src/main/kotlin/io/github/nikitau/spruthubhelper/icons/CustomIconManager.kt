package io.github.nikitau.spruthubhelper.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Stores user-picked icons in private app storage under a hash of the control id. */
class CustomIconManager(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "custom_icons")

    fun hasIcon(controlId: String): Boolean = iconFile(controlId).isFile

    /** Lightweight cache key for Android surfaces that already rendered this icon. */
    fun revision(controlId: String): String? = iconFile(controlId)
        .takeIf(File::isFile)
        ?.let { file -> "${file.length()}:${file.lastModified()}" }

    fun loadIcon(controlId: String): Icon? = loadBitmap(controlId)?.let(Icon::createWithBitmap)

    fun loadBitmap(controlId: String): Bitmap? = iconFile(controlId)
        .takeIf(File::isFile)
        ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    fun save(controlId: String, source: Uri): Result<Unit> = runCatching {
        directory.mkdirs()
        val bitmap = decodeBounded(source)
        val normalized = fitOnTransparentSquare(bitmap)
        val destination = iconFile(controlId)
        val temporary = File(directory, "${destination.name}.new")
        temporary.outputStream().buffered().use { output ->
            check(normalized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Не удалось сохранить PNG"
            }
        }
        check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = true).let { temporary.delete(); true }) {
            "Не удалось заменить файл иконки"
        }
        if (bitmap !== normalized) bitmap.recycle()
        normalized.recycle()
    }

    fun remove(controlId: String): Boolean = iconFile(controlId).delete()

    private fun decodeBounded(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(appContext.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > MAX_SOURCE_SIZE) {
                val scale = MAX_SOURCE_SIZE.toFloat() / largest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun fitOnTransparentSquare(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)
        val available = ICON_SIZE - ICON_PADDING * 2f
        val scale = minOf(available / source.width, available / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val left = (ICON_SIZE - width) / 2f
        val top = (ICON_SIZE - height) / 2f
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return result
    }

    private fun iconFile(controlId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(controlId.toByteArray(Charsets.UTF_8))
            .take(20)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.png")
    }

    private companion object {
        const val MAX_SOURCE_SIZE = 1_024
        const val ICON_SIZE = 192
        const val ICON_PADDING = 18
    }
}
