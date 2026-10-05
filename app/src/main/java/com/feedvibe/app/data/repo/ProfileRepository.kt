package com.feedvibe.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Foto de perfil personalizada (cámara / galería). Se guarda en local a 512px y se
 * sincroniza una miniatura de 256px con el resto de dispositivos.
 */
class ProfileRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val cloud: CloudSync,
) {
    private val file get() = File(context.filesDir, "profile.jpg")
    private val stampFile get() = File(context.filesDir, "profile.stamp")

    /** Ruta de la foto (cambia de "versión" para que Coil recargue la imagen). */
    val photo: Flow<String?> = settings.profilePhotoVersion.map { v ->
        if (file.exists()) "${file.absolutePath}?v=$v" else null
    }

    fun photoFile(): File? = file.takeIf { it.exists() }

    private fun localStamp(): Long = runCatching { stampFile.readText().toLong() }.getOrDefault(0)

    suspend fun setPhoto(uri: Uri) = withContext(Dispatchers.IO) {
        val src = decode(uri) ?: error("No se pudo leer la imagen")
        val square = centerSquare(src, 512)
        file.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val now = System.currentTimeMillis()
        stampFile.writeText(now.toString())
        settings.bumpProfilePhoto()
        cloud.pushProfile(null, toBase64(Bitmap.createScaledBitmap(square, 256, 256, true)), now)
    }

    suspend fun removePhoto() = withContext(Dispatchers.IO) {
        file.delete()
        val now = System.currentTimeMillis()
        stampFile.writeText(now.toString())
        settings.bumpProfilePhoto()
        cloud.pushProfile(null, null, now)
    }

    suspend fun setNickname(name: String) {
        settings.update { it.copy(nickname = name.trim()) }
        cloud.pushProfile(name.trim(), null, null)
    }

    /** Perfil recibido desde otro dispositivo. */
    suspend fun applyRemote(nickname: String?, photoBase64: String?, photoUpdatedAt: Long) = withContext(Dispatchers.IO) {
        if (nickname != null && nickname != settings.current().nickname) settings.update { it.copy(nickname = nickname) }
        if (photoUpdatedAt > localStamp()) {
            if (photoBase64.isNullOrEmpty()) file.delete()
            else {
                val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                file.writeBytes(bytes)
            }
            stampFile.writeText(photoUpdatedAt.toString())
            settings.bumpProfilePhoto()
        }
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val max = maxOf(info.size.width, info.size.height)
                if (max > 2048) d.setTargetSampleSize(max / 1024)
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    private fun centerSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    private fun toBase64(b: Bitmap): String {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
