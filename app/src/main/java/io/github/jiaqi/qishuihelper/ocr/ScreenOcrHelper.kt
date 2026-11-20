package io.github.jiaqi.qishuihelper.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.jiaqi.qishuihelper.model.MatchSource
import io.github.jiaqi.qishuihelper.model.TargetMatch
import io.github.jiaqi.qishuihelper.util.AppLogBus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max

data class OcrLineRecord(
    val text: String,
    val normalizedText: String,
    val bounds: Rect
)

class ScreenOcrHelper {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * 通过 Android 11+ 无障碍静默截屏 + ML Kit 中文离线模型识别当前屏幕所有文字行
     */
    suspend fun captureAndRecognize(
        service: AccessibilityService,
        excludeRect: Rect? = null
    ): List<OcrLineRecord> {
        val bitmap = takeSilentScreenshot(service) ?: return emptyList()
        return try {
            recognizeBitmap(bitmap, excludeRect)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
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
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    hardwareBuffer,
                                    screenshot.colorSpace
                                )
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
                            AppLogBus.warn("无障碍截屏暂不可用(errorCode=$errorCode)")
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
        excludeRect: Rect?
    ): List<OcrLineRecord> = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val records = mutableListOf<OcrLineRecord>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (excludeRect != null && Rect.intersects(box, excludeRect)) {
                            continue
                        }
                        val raw = line.text.trim()
                        if (raw.isEmpty()) continue
                        records.add(
                            OcrLineRecord(
                                text = raw,
                                normalizedText = raw.replace(" ", ""),
                                bounds = Rect(box)
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
     * 在 OCR 结果中查找匹配关键词，并对诸如「奖励已领取 | 关闭」这类合并胶囊按字符位置切分精确子坐标框
     */
    fun findBestMatch(
        lines: List<OcrLineRecord>,
        keywords: List<String>,
        excludeContaining: List<String> = emptyList(),
        regionPredicate: ((Rect) -> Boolean)? = null
    ): TargetMatch? {
        for (keyword in keywords) {
            val cleanKey = keyword.replace(" ", "")
            if (cleanKey.isEmpty()) continue

            for (line in lines) {
                if (excludeContaining.any { ex -> line.normalizedText.contains(ex) }) {
                    continue
                }
                // 单字关键词（如「免」）要求整行较短，避免误中歌词中的普通汉字
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
}
