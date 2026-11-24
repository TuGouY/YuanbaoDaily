package me.codex.yuanbaodaily.task

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import me.codex.yuanbaodaily.hook.Log
import java.util.ArrayDeque
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class PictureScene {
    LANDSCAPE,
    PORTRAIT,
    ANIMAL,
    CITY,
    FLOWER,
}

internal data class GeneratedTaskMedia(
    val uri: Uri,
    val displayName: String,
    val pictureScene: PictureScene? = null,
) {
    val marker: String get() = displayName.substringBeforeLast('.')
}

/** Generates fresh local input so image-based tasks need no user-prepared files. */
internal class TaskMediaFactory(private val context: Context) {
    private val random = Random(System.nanoTime())
    /** 复用窗口：这段时间内生成过的素材直接复用（用户要求不必每次新建）。 */
    private val reuseWindowMs = 12L * 60L * 60L * 1000L
    private val pictureSceneQueue = ArrayDeque<PictureScene>()
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "YuanbaoDailyMediaCleanup").apply { isDaemon = true }
    }
    private val mediaLock = Any()
    private val preferences = context.getSharedPreferences(MEDIA_PREFS, Context.MODE_PRIVATE)

    init {
        cleanupExpired()
    }

    fun create(kind: DailyTaskKind, attempt: Int): GeneratedTaskMedia? {
        cleanupExpired()
        // 按用户要求：每次进入相册时生成一张新图，任务结束"用完就删"。
        val scene = if (kind == DailyTaskKind.PIC) {
            nextPictureScene()
        } else {
            null
        }
        val bitmap = when (kind) {
            DailyTaskKind.PROBLEM -> problemBitmap(attempt)
            DailyTaskKind.PIC -> pictureBitmap(scene ?: PictureScene.LANDSCAPE)
            DailyTaskKind.TEMPLATE -> templateBitmap(attempt)
            else -> return null
        }
        val displayName = "YuanbaoDaily_AUTO_${kind.id}_${System.currentTimeMillis()}_$attempt.png"
        return try {
            save(bitmap, displayName, scene)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * MediaStore **集合**里能不能按名字查到这张图。
     *
     * 为什么要单独查一次集合：`save()` 校验的是**我们插入的那条 uri**，
     * 而元宝的相册/选择器是**查整个集合**。两者不是一回事 ——
     * 真机上见过"uri 查得到、集合里查不到"的时机差（provider 刚 publish、
     * 索引还没扩散），表现就是用户说的"进了相册但没有素材照片"。
     * 所以打开选择器之前必须用这个判据确认一次。
     */
    fun isIndexed(displayName: String): Boolean = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { it.count > 0 } ?: false
    }.onFailure { Log.e("media index query failed name=$displayName", it) }.getOrDefault(false)

    /** 轮询等这张图真正出现在 MediaStore 集合里。 */
    fun awaitIndexed(displayName: String, timeoutMs: Long = MEDIA_INDEX_WAIT_MS): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (isIndexed(displayName)) return true
            SystemClock.sleep(MEDIA_PUBLISH_POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return isIndexed(displayName)
    }

    /** Removes only media rows created and tracked by this module. */
    fun cleanupGeneratedMedia(): Int = cleanup(force = true)

    /** 只清理超出保留窗口的素材，保留最近生成的那张（供复用）。 */
    fun cleanupExpiredMedia(): Int = cleanup(force = false)

    private fun nextPictureScene(): PictureScene = synchronized(pictureSceneQueue) {
        if (pictureSceneQueue.isEmpty()) {
            val shuffled = PictureScene.entries.toMutableList()
            Collections.shuffle(shuffled, random)
            pictureSceneQueue.addAll(shuffled)
        }
        pictureSceneQueue.removeFirst()
    }

    private fun save(
        bitmap: Bitmap,
        displayName: String,
        scene: PictureScene?,
    ): GeneratedTaskMedia? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YuanbaoDaily")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } == true
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        val publishUpdates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } else {
            1
        }
        if (publishUpdates <= 0) {
            Log.d("media publish failed uri=$uri name=$displayName updateCount=$publishUpdates")
            resolver.delete(uri, null, null)
            return null
        }
        // **补写时间戳**：真机实测（`self test gen`）插入时给的 DATE_TAKEN 会被
        // MediaProvider 丢掉（PNG 里没有 EXIF，ColorOS 侧按 EXIF 派生），
        // 回读拿到 `dateTaken=0`。后果很直接 —— 相册/选择器按时间排序时这张图
        // 排到**最后**，用户打开相册只看首屏就会认为"根本没生成素材照片"。
        // 所以发布之后再显式写一次，并回读确认。
        val stamp = System.currentTimeMillis()
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, stamp)
                    put(MediaStore.Images.Media.DATE_MODIFIED, stamp / 1000L)
                },
                null,
                null,
            )
        }.onFailure { Log.e("media timestamp update failed uri=$uri", it) }

        resolver.notifyChange(uri, null)
        resolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
        val published = awaitPublished(uri, displayName)
        Log.d(
            "media publish uri=$uri name=${published?.displayName ?: displayName} " +
                "size=${published?.sizeBytes ?: -1L} pending=${published?.pending ?: -1} " +
                "mime=${published?.mimeType.orEmpty()} path=${published?.relativePath.orEmpty()} " +
                "dateTaken=${published?.dateTaken ?: 0L} dateAdded=${published?.dateAdded ?: 0L} " +
                "id=${published?.id ?: -1L} " +
                "updateCount=$publishUpdates ready=${MediaPublicationVerifier.isReady(displayName, published)}",
        )
        if (!MediaPublicationVerifier.isReady(displayName, published)) {
            resolver.delete(uri, null, null)
            return null
        }
        val generated = GeneratedTaskMedia(uri, displayName, scene)
        trackForCleanup(generated)
        return generated
    }

    private fun awaitPublished(uri: Uri, displayName: String): MediaPublicationSnapshot? {
        val deadline = SystemClock.uptimeMillis() + MEDIA_PUBLISH_TIMEOUT_MS
        var latest: MediaPublicationSnapshot? = null
        do {
            latest = queryPublication(uri)
            if (MediaPublicationVerifier.isReady(displayName, latest)) return latest
            SystemClock.sleep(MEDIA_PUBLISH_POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return queryPublication(uri) ?: latest
    }

    private fun queryPublication(uri: Uri): MediaPublicationSnapshot? {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.IS_PENDING)
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun string(column: String): String = cursor.getColumnIndex(column)
                    .takeIf { it >= 0 }
                    ?.let(cursor::getString)
                    .orEmpty()
                fun long(column: String, default: Long = 0L): Long = cursor.getColumnIndex(column)
                    .takeIf { it >= 0 }
                    ?.let(cursor::getLong)
                    ?: default
                MediaPublicationSnapshot(
                    displayName = string(MediaStore.Images.Media.DISPLAY_NAME),
                    sizeBytes = long(MediaStore.Images.Media.SIZE, -1L),
                    pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        long(MediaStore.Images.Media.IS_PENDING, -1L).toInt()
                    } else {
                        0
                    },
                    id = long(MediaStore.Images.Media._ID, -1L),
                    mimeType = string(MediaStore.Images.Media.MIME_TYPE),
                    relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        string(MediaStore.Images.Media.RELATIVE_PATH)
                    } else {
                        ""
                    },
                    dateTaken = long(MediaStore.Images.Media.DATE_TAKEN),
                    dateAdded = long(MediaStore.Images.Media.DATE_ADDED),
                )
            }
        }.onFailure { Log.e("media publication query failed uri=$uri", it) }.getOrNull()
    }

    private fun trackForCleanup(media: GeneratedTaskMedia) {
        val record = listOf(
            media.uri.toString(),
            System.currentTimeMillis().toString(),
            media.displayName,
        ).joinToString(RECORD_SEPARATOR)
        synchronized(mediaLock) {
            val pending = preferences.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toMutableSet()
            pending.add(record)
            preferences.edit().putStringSet(KEY_PENDING_MEDIA, pending).apply()
        }
        cleanupExecutor.schedule(
            { cleanupUri(media.uri.toString()) },
            CLEANUP_DELAY_MINUTES,
            TimeUnit.MINUTES,
        )
    }

    private fun cleanupExpired(): Int = cleanup(force = false)

    private fun cleanup(force: Boolean): Int {
        val now = System.currentTimeMillis()
        val records = synchronized(mediaLock) {
            preferences.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toSet()
        }
        if (records.isEmpty()) return 0

        var removed = 0
        val remaining = linkedSetOf<String>()
        for (record in records) {
            val fields = record.split(RECORD_SEPARATOR, limit = 3)
            val createdAt = fields.getOrNull(1)?.toLongOrNull()
            val due = force || createdAt == null || now - createdAt >= CLEANUP_DELAY_MS
            if (!due) {
                remaining.add(record)
                continue
            }
            val uri = fields.firstOrNull()?.let(Uri::parse)
            val deleted = uri != null && runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.onFailure { Log.e("media cleanup failed uri=$uri", it) }.getOrDefault(false)
            if (deleted) removed++ else remaining.add(record)
        }
        synchronized(mediaLock) {
            val newlyAdded = preferences.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty()
                .filterNot(records::contains)
            preferences.edit()
                .putStringSet(KEY_PENDING_MEDIA, (remaining + newlyAdded).toSet())
                .apply()
        }
        if (removed > 0) Log.d("media cleanup removed=$removed force=$force")
        return removed
    }

    private fun cleanupUri(uriValue: String) {
        val records = synchronized(mediaLock) {
            preferences.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toSet()
        }
        val matched = records.filter { it.substringBefore(RECORD_SEPARATOR) == uriValue }
        if (matched.isEmpty()) return
        val deleted = runCatching {
            context.contentResolver.delete(Uri.parse(uriValue), null, null) > 0
        }.onFailure { Log.e("delayed media cleanup failed uri=$uriValue", it) }.getOrDefault(false)
        if (!deleted) return
        synchronized(mediaLock) {
            val current = preferences.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toMutableSet()
            current.removeAll(matched.toSet())
            preferences.edit().putStringSet(KEY_PENDING_MEDIA, current).apply()
        }
        Log.d("delayed media cleanup removed uri=$uriValue")
    }

    private fun pictureBitmap(scene: PictureScene): Bitmap = when (scene) {
        PictureScene.LANDSCAPE -> landscapeBitmap()
        PictureScene.PORTRAIT -> portraitBitmap()
        PictureScene.ANIMAL -> animalBitmap()
        PictureScene.CITY -> cityBitmap()
        PictureScene.FLOWER -> flowerBitmap()
    }

    private fun landscapeBitmap(): Bitmap = canvasBitmap { canvas, paint, width, height ->
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            Color.rgb(103, 176, 224),
            Color.rgb(244, 211, 146),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.color = Color.rgb(255, 229, 138)
        canvas.drawCircle(width * 0.78f, height * 0.20f, 105f, paint)

        paint.color = Color.rgb(80, 112, 116)
        canvas.drawPath(Path().apply {
            moveTo(0f, height * 0.56f)
            lineTo(width * 0.22f, height * 0.28f)
            lineTo(width * 0.42f, height * 0.54f)
            lineTo(width * 0.63f, height * 0.24f)
            lineTo(width, height * 0.57f)
            close()
        }, paint)
        paint.color = Color.rgb(230, 238, 228)
        canvas.drawPath(Path().apply {
            moveTo(width * 0.15f, height * 0.37f)
            lineTo(width * 0.22f, height * 0.28f)
            lineTo(width * 0.30f, height * 0.38f)
            lineTo(width * 0.25f, height * 0.36f)
            lineTo(width * 0.21f, height * 0.40f)
            close()
            moveTo(width * 0.54f, height * 0.36f)
            lineTo(width * 0.63f, height * 0.24f)
            lineTo(width * 0.74f, height * 0.38f)
            lineTo(width * 0.65f, height * 0.34f)
            lineTo(width * 0.60f, height * 0.39f)
            close()
        }, paint)

        paint.shader = LinearGradient(
            0f,
            height * 0.54f,
            0f,
            height,
            Color.rgb(88, 167, 182),
            Color.rgb(28, 104, 126),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height * 0.54f, width, height, paint)
        paint.shader = null
        paint.color = Color.argb(120, 255, 231, 165)
        repeat(8) { index ->
            val y = height * (0.62f + index * 0.035f)
            val half = 60f + index * 35f
            canvas.drawRoundRect(
                RectF(width * 0.78f - half, y, width * 0.78f + half, y + 7f),
                4f,
                4f,
                paint,
            )
        }
        drawPine(canvas, paint, 115f, height * 0.66f, 0.92f)
        drawPine(canvas, paint, 245f, height * 0.72f, 0.66f)
        drawPine(canvas, paint, width - 125f, height * 0.70f, 0.78f)
    }

    private fun portraitBitmap(): Bitmap = canvasBitmap { canvas, paint, width, height ->
        paint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            Color.rgb(213, 232, 225),
            Color.rgb(241, 206, 194),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.color = Color.argb(105, 255, 255, 255)
        canvas.drawCircle(185f, 240f, 150f, paint)
        canvas.drawCircle(width - 130f, height * 0.34f, 210f, paint)

        paint.color = Color.rgb(50, 48, 57)
        canvas.drawOval(RectF(250f, 160f, 830f, 910f), paint)
        paint.color = Color.rgb(230, 177, 143)
        canvas.drawRoundRect(RectF(466f, 730f, 614f, 1000f), 50f, 50f, paint)
        canvas.drawOval(RectF(315f, 245f, 765f, 825f), paint)
        paint.color = Color.rgb(50, 48, 57)
        canvas.drawPath(Path().apply {
            moveTo(300f, 390f)
            cubicTo(350f, 180f, 735f, 140f, 785f, 425f)
            cubicTo(670f, 315f, 520f, 285f, 300f, 390f)
            close()
        }, paint)
        paint.color = Color.WHITE
        canvas.drawOval(RectF(385f, 500f, 485f, 552f), paint)
        canvas.drawOval(RectF(590f, 500f, 690f, 552f), paint)
        paint.color = Color.rgb(44, 75, 72)
        canvas.drawCircle(438f, 527f, 19f, paint)
        canvas.drawCircle(642f, 527f, 19f, paint)
        paint.color = Color.rgb(193, 103, 105)
        canvas.drawOval(RectF(468f, 690f, 612f, 738f), paint)

        paint.color = Color.rgb(65, 104, 121)
        canvas.drawOval(RectF(155f, 880f, width - 155f, height + 260f), paint)
        paint.color = Color.rgb(244, 221, 202)
        canvas.drawCircle(165f, height - 175f, 62f, paint)
        canvas.drawCircle(width - 165f, height - 175f, 62f, paint)
    }

    private fun animalBitmap(): Bitmap = canvasBitmap { canvas, paint, width, height ->
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            Color.rgb(188, 222, 234),
            Color.rgb(219, 224, 174),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.color = Color.rgb(88, 139, 88)
        canvas.drawRect(0f, height * 0.70f, width, height, paint)

        val fur = if (random.nextBoolean()) Color.rgb(212, 153, 87) else Color.rgb(137, 134, 128)
        paint.color = fur
        canvas.drawOval(RectF(250f, 600f, 865f, 1220f), paint)
        canvas.drawCircle(width * 0.50f, 500f, 280f, paint)
        canvas.drawPath(Path().apply {
            moveTo(300f, 335f)
            lineTo(330f, 95f)
            lineTo(500f, 300f)
            close()
            moveTo(580f, 300f)
            lineTo(750f, 95f)
            lineTo(780f, 335f)
            close()
        }, paint)
        paint.color = Color.rgb(238, 188, 170)
        canvas.drawPath(Path().apply {
            moveTo(335f, 285f)
            lineTo(350f, 150f)
            lineTo(447f, 292f)
            close()
            moveTo(633f, 292f)
            lineTo(730f, 150f)
            lineTo(745f, 285f)
            close()
        }, paint)
        paint.color = Color.WHITE
        canvas.drawOval(RectF(350f, 430f, 485f, 545f), paint)
        canvas.drawOval(RectF(595f, 430f, 730f, 545f), paint)
        paint.color = Color.rgb(53, 78, 65)
        canvas.drawCircle(420f, 488f, 29f, paint)
        canvas.drawCircle(660f, 488f, 29f, paint)
        paint.color = Color.rgb(78, 55, 49)
        canvas.drawRoundRect(RectF(500f, 585f, 580f, 642f), 28f, 28f, paint)
        paint.strokeWidth = 8f
        paint.style = Paint.Style.STROKE
        canvas.drawPath(Path().apply {
            moveTo(540f, 640f)
            cubicTo(515f, 682f, 475f, 685f, 452f, 660f)
            moveTo(540f, 640f)
            cubicTo(565f, 682f, 605f, 685f, 628f, 660f)
        }, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(248, 218, 95)
        repeat(7) { index ->
            val x = 85f + index * 155f
            val y = height - 90f - (index % 2) * 45f
            canvas.drawCircle(x, y, 32f, paint)
        }
    }

    private fun cityBitmap(): Bitmap = canvasBitmap { canvas, paint, width, height ->
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            Color.rgb(52, 68, 115),
            Color.rgb(226, 134, 123),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.color = Color.rgb(246, 211, 151)
        canvas.drawCircle(width * 0.72f, height * 0.25f, 92f, paint)

        val buildingColors = intArrayOf(
            Color.rgb(40, 49, 66),
            Color.rgb(57, 65, 80),
            Color.rgb(34, 43, 58),
            Color.rgb(73, 69, 78),
        )
        var x = 0f
        var building = 0
        while (x < width) {
            val buildingWidth = 120f + random.nextInt(90)
            val top = height * (0.35f + random.nextFloat() * 0.30f)
            paint.color = buildingColors[building % buildingColors.size]
            canvas.drawRect(x, top, (x + buildingWidth).coerceAtMost(width), height, paint)
            paint.color = Color.rgb(247, 210, 112)
            var windowY = top + 60f
            while (windowY < height * 0.82f) {
                var windowX = x + 25f
                while (windowX < x + buildingWidth - 25f) {
                    if (random.nextInt(4) != 0) {
                        canvas.drawRect(windowX, windowY, windowX + 24f, windowY + 34f, paint)
                    }
                    windowX += 52f
                }
                windowY += 72f
            }
            x += buildingWidth + 8f
            building++
        }
        paint.color = Color.rgb(35, 39, 48)
        canvas.drawPath(Path().apply {
            moveTo(0f, height)
            lineTo(width * 0.37f, height * 0.76f)
            lineTo(width * 0.63f, height * 0.76f)
            lineTo(width, height)
            close()
        }, paint)
        paint.color = Color.rgb(244, 220, 142)
        repeat(5) { index ->
            val y = height * (0.82f + index * 0.04f)
            val half = 14f + index * 22f
            canvas.drawRect(width / 2f - half, y, width / 2f + half, y + 11f, paint)
        }
    }

    private fun flowerBitmap(): Bitmap = canvasBitmap { canvas, paint, width, height ->
        paint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            Color.rgb(239, 229, 215),
            Color.rgb(194, 220, 210),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.color = Color.rgb(151, 107, 81)
        canvas.drawRect(0f, height * 0.78f, width, height, paint)
        paint.color = Color.rgb(91, 143, 139)
        canvas.drawOval(RectF(360f, 690f, 720f, 1260f), paint)
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawOval(RectF(425f, 730f, 520f, 1170f), paint)

        val centers = listOf(
            330f to 385f,
            540f to 260f,
            760f to 430f,
            455f to 520f,
            665f to 580f,
        )
        paint.strokeWidth = 17f
        paint.color = Color.rgb(64, 126, 83)
        centers.forEach { (cx, cy) ->
            canvas.drawLine(540f, 810f, cx, cy + 45f, paint)
            canvas.drawOval(RectF(cx - 95f, cy + 105f, cx - 15f, cy + 170f), paint)
        }
        val petalColors = intArrayOf(
            Color.rgb(226, 105, 116),
            Color.rgb(245, 185, 86),
            Color.rgb(137, 126, 195),
            Color.rgb(239, 145, 182),
            Color.rgb(245, 236, 215),
        )
        centers.forEachIndexed { index, (cx, cy) ->
            paint.color = petalColors[index % petalColors.size]
            repeat(8) { petal ->
                val angle = Math.toRadians(petal * 45.0)
                val px = cx + Math.cos(angle).toFloat() * 70f
                val py = cy + Math.sin(angle).toFloat() * 70f
                canvas.drawOval(RectF(px - 42f, py - 62f, px + 42f, py + 62f), paint)
            }
            paint.color = Color.rgb(101, 73, 51)
            canvas.drawCircle(cx, cy, 42f, paint)
        }
    }

    private fun drawPine(canvas: Canvas, paint: Paint, x: Float, baseY: Float, scale: Float) {
        paint.color = Color.rgb(55, 83, 60)
        canvas.drawPath(Path().apply {
            moveTo(x, baseY - 300f * scale)
            lineTo(x - 130f * scale, baseY)
            lineTo(x + 130f * scale, baseY)
            close()
        }, paint)
        paint.color = Color.rgb(93, 67, 48)
        canvas.drawRect(x - 15f * scale, baseY, x + 15f * scale, baseY + 100f * scale, paint)
    }

    private fun problemBitmap(attempt: Int): Bitmap {
        val left = 12 + random.nextInt(28)
        val right = 3 + random.nextInt(9)
        val add = 5 + random.nextInt(35)
        return canvasBitmap { canvas, paint, width, height ->
            canvas.drawColor(Color.WHITE)
            paint.color = Color.rgb(31, 42, 68)
            drawCentered(canvas, paint, "MATH PRACTICE", 210f, 54f)
            paint.color = Color.rgb(226, 236, 248)
            canvas.drawRoundRect(RectF(90f, 310f, width - 90f, height - 250f), 36f, 36f, paint)
            paint.color = Color.rgb(25, 35, 56)
            drawCentered(canvas, paint, "$left x $right + $add = ?", height * 0.54f, 96f)
            paint.color = Color.rgb(71, 121, 185)
            drawCentered(canvas, paint, "Show the steps  #$attempt", height * 0.72f, 42f)
        }
    }

    private fun templateBitmap(attempt: Int): Bitmap = canvasBitmap { canvas, paint, width, height ->
        val backgrounds = listOf(
            Color.rgb(220, 235, 230),
            Color.rgb(232, 225, 241),
            Color.rgb(226, 235, 247),
        )
        canvas.drawColor(backgrounds[(attempt - 1).mod(backgrounds.size)])
        paint.color = Color.rgb(42, 61, 70)
        canvas.drawRect(0f, 0f, width, 190f, paint)

        paint.color = Color.rgb(66, 103, 124)
        canvas.drawOval(RectF(170f, 900f, width - 170f, height + 180f), paint)
        paint.color = Color.rgb(229, 178, 142)
        canvas.drawRoundRect(RectF(455f, 770f, 625f, 990f), 55f, 55f, paint)
        paint.color = Color.rgb(52, 39, 36)
        canvas.drawOval(RectF(280f, 285f, 800f, 900f), paint)
        paint.color = Color.rgb(240, 194, 158)
        canvas.drawOval(RectF(320f, 330f, 760f, 865f), paint)
        canvas.drawCircle(320f, 610f, 46f, paint)
        canvas.drawCircle(760f, 610f, 46f, paint)
        paint.color = Color.rgb(52, 39, 36)
        canvas.drawOval(RectF(375f, 470f, 495f, 520f), paint)
        canvas.drawOval(RectF(585f, 470f, 705f, 520f), paint)
        paint.color = Color.WHITE
        canvas.drawOval(RectF(392f, 480f, 482f, 525f), paint)
        canvas.drawOval(RectF(598f, 480f, 688f, 525f), paint)
        paint.color = Color.rgb(47, 69, 76)
        canvas.drawCircle(440f, 503f, 20f, paint)
        canvas.drawCircle(640f, 503f, 20f, paint)
        paint.color = Color.rgb(202, 126, 105)
        canvas.drawOval(RectF(455f, 710f, 625f, 760f), paint)
        paint.color = Color.rgb(213, 151, 121)
        canvas.drawRoundRect(RectF(515f, 545f, 565f, 680f), 25f, 25f, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, paint, "DAILY MOMENT $attempt", 145f, 52f)
    }

    private fun canvasBitmap(draw: (Canvas, Paint, Float, Float) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), Paint(Paint.ANTI_ALIAS_FLAG), 1080f, 1350f)
        return bitmap
    }

    private fun drawCentered(canvas: Canvas, paint: Paint, value: String, y: Float, size: Float) {
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD,
        )
        canvas.drawText(value, canvas.width / 2f, y, paint)
    }

    private companion object {
        const val MEDIA_PREFS = "yuanbao_daily_generated_media"
        const val KEY_PENDING_MEDIA = "pending_media"
        const val RECORD_SEPARATOR = "\t"
        /** 等"集合里能查到这张图"的上限 —— 相册/选择器查的是集合，不是我们那条 uri。 */
        const val MEDIA_INDEX_WAIT_MS = 6_000L
        const val CLEANUP_DELAY_MINUTES = 15L
        const val CLEANUP_DELAY_MS = CLEANUP_DELAY_MINUTES * 60L * 1000L
        const val MEDIA_PUBLISH_TIMEOUT_MS = 6_000L
        const val MEDIA_PUBLISH_POLL_MS = 150L
    }
}
