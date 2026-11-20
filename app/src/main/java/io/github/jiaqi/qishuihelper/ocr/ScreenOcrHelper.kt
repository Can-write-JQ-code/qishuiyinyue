package io.github.jiaqi.qishuihelper.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.jiaqi.qishuihelper.model.MatchSource
import io.github.jiaqi.qishuihelper.model.TargetMatch
import io.github.jiaqi.qishuihelper.util.AppLogBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

data class OcrLineRecord(
    val text: String,
    val normalizedText: String,
    val bounds: Rect
)

class ScreenOcrHelper {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    @Volatile
    private var lastScreenshotUptimeMs: Long = 0L

    /**
     * 通过 Android 11+ 无障碍静默截屏 + 等比缩放加速 + ML Kit 中文离线模型识别全屏文字
     */
    suspend fun captureAndRecognize(
        service: AccessibilityService,
        excludeRect: Rect? = null
    ): List<OcrLineRecord> {
        // 遵守 Android 11/12 截屏最小间隔限制，避免报 errorCode=3 (INTERVAL_TIME_SHORT)
        val elapsed = SystemClock.uptimeMillis() - lastScreenshotUptimeMs
        if (elapsed in 0..1149L) {
            delay(1150L - elapsed)
        }

        val rawBitmap = takeSilentScreenshot(service) ?: return emptyList()
        lastScreenshotUptimeMs = SystemClock.uptimeMillis()

        var scaledBitmap: Bitmap? = null
        return try {
            val origW = rawBitmap.width
            val origH = rawBitmap.height
            val scale = if (origW > MAX_OCR_WIDTH) {
                MAX_OCR_WIDTH.toFloat() / origW.toFloat()
            } else {
                1.0f
            }
            val bitmapForOcr = if (scale < 0.999f) {
                val targetW = (origW * scale).roundToInt().coerceAtLeast(1)
                val targetH = (origH * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true).also {
                    scaledBitmap = it
                }
            } else {
                rawBitmap
            }
            recognizeBitmap(bitmapForOcr, 1.0f / scale, excludeRect)
        } finally {
            if (scaledBitmap != null && scaledBitmap !== rawBitmap && !scaledBitmap!!.isRecycled) {
                scaledBitmap!!.recycle()
            }
            if (!rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }
        }
    }

    private suspend fun takeSilentScreenshot(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            try {
                                val hwBitmap = runCatching {
                                    Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                }.getOrNull() ?: Bitmap.wrapHardwareBuffer(hardwareBuffer, null)

                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()
                                if (cont.isActive) {
                                    cont.resume(softBitmap)
                                } else {
                                    softBitmap?.recycle()
                                }
                            } catch (t: Throwable) {
                                AppLogBus.warn("OCR 截屏像素转换失败：${t.message}")
                                if (cont.isActive) cont.resume(null)
                            } finally {
                                hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            AppLogBus.warn("无障碍静默截屏失败(errorCode=$errorCode)")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                AppLogBus.warn("调用无障碍截屏异常：${t.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    private suspend fun recognizeBitmap(
        bitmap: Bitmap,
        invScale: Float,
        excludeRect: Rect?
    ): List<OcrLineRecord> = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val records = mutableListOf<OcrLineRecord>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val rawBox = line.boundingBox ?: continue
                        val mappedBox = Rect(
                            (rawBox.left * invScale).roundToInt(),
                            (rawBox.top * invScale).roundToInt(),
                            (rawBox.right * invScale).roundToInt(),
                            (rawBox.bottom * invScale).roundToInt()
                        )
                        if (excludeRect != null && Rect.intersects(mappedBox, excludeRect)) {
                            continue
                        }
                        val raw = line.text.trim()
                        val norm = normalizeText(raw)
                        if (norm.isEmpty()) continue
                        records.add(
                            OcrLineRecord(
                                text = raw,
                                normalizedText = norm,
                                bounds = mappedBox
                            )
                        )
                    }
                }
                if (cont.isActive) cont.resume(records)
            }
            .addOnFailureListener { err ->
                AppLogBus.warn("ML Kit 中文识别异常：${err.message}")
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    /**
     * 在 OCR 结果中查找匹配关键词，支持按字符比例切分合并胶囊坐标（如「奖励已领取 | 跳过」）
     */
    fun findBestMatch(
        lines: List<OcrLineRecord>,
        keywords: List<String>,
        excludeContaining: List<String> = emptyList(),
        regionPredicate: ((Rect) -> Boolean)? = null
    ): TargetMatch? {
        for (keyword in keywords) {
            val cleanKey = normalizeText(keyword)
            if (cleanKey.isEmpty()) continue

            for (line in lines) {
                if (excludeContaining.any { ex -> line.normalizedText.contains(normalizeText(ex)) }) {
                    continue
                }
                if (cleanKey.length == 1 && line.normalizedText.length > 4) {
                    continue
                }
                val startIdx = line.normalizedText.indexOf(cleanKey)
                if (startIdx < 0) continue

                val subBounds = sliceHorizontalBounds(
                    lineRect = line.bounds,
                    totalChars = line.normalizedText.length,
                    matchStart = startIdx,
                    matchLen = cleanKey.length
                )
                if (regionPredicate != null && !regionPredicate(subBounds)) {
                    continue
                }
                return TargetMatch(
                    matchedKeyword = keyword,
                    fullText = line.text,
                    bounds = subBounds,
                    source = MatchSource.OCR
                )
            }
        }
        return null
    }

    private fun sliceHorizontalBounds(
        lineRect: Rect,
        totalChars: Int,
        matchStart: Int,
        matchLen: Int
    ): Rect {
        if (totalChars <= 0 || (matchStart == 0 && matchLen >= totalChars)) {
            return Rect(lineRect)
        }
        val startRatio = matchStart.toFloat() / totalChars.toFloat()
        val endRatio = (matchStart + matchLen).coerceAtMost(totalChars).toFloat() / totalChars.toFloat()
        val subLeft = (lineRect.left + lineRect.width() * startRatio).toInt()
        val subRight = (lineRect.left + lineRect.width() * endRatio).toInt()
        return Rect(
            subLeft,
            lineRect.top,
            max(subLeft + 18, subRight),
            lineRect.bottom
        )
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        private const val MAX_OCR_WIDTH = 1080
        private val DECOR_EDGE_REGEX = Regex("^[×xX✕✖·•|丨\\[\\]【】()（）<>《》\\s]+|[×xX✕✖·•|丨\\[\\]【】()（）<>《》\\s]+$")

        /**
         * 归一化 OCR 常见形近字混淆与首尾符号（参考开源社区实测修正表）
         */
        fun normalizeText(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace(" ", "")
                .replace("\u3000", "")
                .replace("，", ",")
                .replace(DECOR_EDGE_REGEX, "")
                .replace("领収", "领取")
                .replace("领职", "领取")
                .replace("领敢", "领取")
                .replace("奨", "奖")
                .replace("奬", "奖")
                .replace("勵", "励")
                .replace("时常", "时长")
                .replace("時長", "时长")
                .replace("福利", "福利")
        }
    }
}
