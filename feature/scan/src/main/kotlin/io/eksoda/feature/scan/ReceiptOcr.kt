package io.eksoda.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Runs synchronously on the caller's background dispatcher. Never sends receipt images anywhere. */
internal object ReceiptOcr {
    fun read(context: Context, uri: Uri): String? {
        val root = File(context.filesDir, "receipt_ocr_4_1")
        val dir = File(root, "tessdata")
        check(dir.isDirectory || dir.mkdirs())
        for (language in listOf("ell", "eng")) {
            val name = "$language.traineddata"
            val target = File(dir, name)
            if (!target.isFile || target.length() == 0L) {
                val temp = File.createTempFile(language, ".part", dir)
                try {
                    context.assets.open("tessdata/$name").use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temp.length() > 0)
                    check(temp.renameTo(target))
                } finally {
                    temp.delete()
                }
            }
        }
        val bitmap = load(context, uri) ?: return null
        try {
            val tess = TessBaseAPI()
            try {
                check(tess.init(root.absolutePath, "ell+eng"))
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                tess.setImage(bitmap)
                return tess.getUTF8Text()?.trim()?.takeIf { it.isNotBlank() }
            } finally {
                tess.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun load(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
