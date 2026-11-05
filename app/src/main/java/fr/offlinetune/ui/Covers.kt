package fr.offlinetune.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover bitmaps from cover.jpg Uris or from the audio file's embedded picture ("embedded:<uri>"). */
object Covers {
    private val cache = LruCache<String, Bitmap>(80)

    fun key(coverUri: String?, trackUri: String?): String? = coverUri ?: trackUri?.let { "embedded:$it" }

    fun cached(key: String, size: Int): Bitmap? = cache.get("$key@$size")

    fun load(context: Context, key: String, size: Int): Bitmap? {
        cached(key, size)?.let { return it }
        val bytes = runCatching {
            if (key.startsWith("embedded:")) {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(context, Uri.parse(key.removePrefix("embedded:"))); r.embeddedPicture } finally { r.release() }
            } else {
                context.contentResolver.openInputStream(Uri.parse(key))?.use { it.readBytes() }
            }
        }.getOrNull() ?: return null
        return fromBytes(key, bytes, size)
    }

    fun fromBytes(key: String, bytes: ByteArray, size: Int): Bitmap? {
        val bmp = decode(bytes, size) ?: return null
        cache.put("$key@$size", bmp)
        return bmp
    }

    fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

@Composable
fun rememberCover(key: String?, size: Int = 256): Bitmap? {
    val context = LocalContext.current
    var bmp by remember(key, size) { mutableStateOf(key?.let { Covers.cached(it, size) }) }
    LaunchedEffect(key, size) {
        if (key != null && bmp == null) bmp = withContext(Dispatchers.IO) { Covers.load(context, key, size) }
    }
    return bmp
}

/** Thumbnail from the network (search results). */
@Composable
fun RemoteImage(url: String?, shape: Shape, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf(url?.let { Covers.cached(it, 160) }) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) bmp = withContext(Dispatchers.IO) {
            runCatching { fr.offlinetune.core.net.Http.getBytes(url) }.getOrNull()?.let { Covers.fromBytes(url, it, 160) }
        }
    }
    BitmapBox(bmp, shape, modifier)
}

/** Image from bytes held in memory (cover of the sheet before download). */
@Composable
fun BytesImage(bytes: ByteArray?, shape: Shape, modifier: Modifier = Modifier, size: Int = 600) {
    val bmp = remember(bytes) { bytes?.let { Covers.decode(it, size) } }
    BitmapBox(bmp, shape, modifier)
}

@Composable
private fun BitmapBox(bmp: Bitmap?, shape: Shape, modifier: Modifier) {
    Box(modifier.clip(shape).background(Ink.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Ink.Muted)
        }
    }
}

@Composable
fun CoverImage(key: String?, shape: Shape, modifier: Modifier = Modifier, size: Int = 256) {
    val bmp = rememberCover(key, size)
    Box(modifier.clip(shape).background(Ink.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Ink.Muted)
        }
    }
}
