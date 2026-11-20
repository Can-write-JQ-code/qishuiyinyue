package io.github.jiaqi.qishuihelper.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.jiaqi.qishuihelper.model.MatchSource
import io.github.jiaqi.qishuihelper.model.PctRegion
import io.github.jiaqi.qishuihelper.model.TargetMatch
import io.github.jiaqi.qishuihelper.util.AppLogBus
import kotlinx.coroutines.delay
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

    @Volatile
    private var lastScreenshotUptimeMs: Long = 0L

    /**
     * Android 13+ 系统截屏限频已放宽至 333ms，Android 11/12 为 1000ms
     */
    private val minIntervalMs: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        380L
    } else {
        1050L
    }

    /**
     * 获取单帧原始屏幕 Bitmap（调用方负责用完 recycle，可用于同一帧连续裁剪多个小区域而不重复截屏）
     */
    suspend fun captureScreenBitmap(service: AccessibilityService): Bitmap? {
        val elapsed = SystemClock.uptimeMillis() - lastScreenshotUptimeMs
        if (elapsed in 0 until minIntervalMs) {
            delay(minIntervalMs - elapsed)
        }
        val firstTry = takeSilentScreenshotOnce(service)
        if (firstTry != null) {
            lastScreenshotUptimeMs = SystemClock.uptimeMillis()
            return firstTry
        }
        // 若偶发截屏间隔过短 (errorCode=3)，等待 450ms 自动重试一次
        delay(450L)
        val retry = takeSilentScreenshotOnce(service)
        if (retry != null) {
            lastScreenshotUptimeMs = SystemClock.uptimeMillis()
        }
        return retry
    }

    /**
     * 核心优化（借鉴 qishuiAScriptProject）：
     * 只裁剪指定百分比小区域送入 ML Kit OCR，耗时仅 30~50ms，识别率大幅提升且天然物理隔离干扰区域！
     */
    suspend fun recognizeInRegion(
        fullBitmap: Bitmap,
        region: PctRegion,
        excludeRect: Rect? = null
    ): List<OcrLineRecord> {
        val bw = fullBitmap.width
        val bh = fullBitmap.height
        if (bw < 32 || bh < 32) return emptyList()

        val cropRect = region.toScreenRect(bw, bh)
        val cropW = cropRect.width().coerceAtMost(bw - cropRect.left)
        val cropH = cropRect.height().coerceAtMost(bh - cropRect.top)
        if (cropW < 16 || cropH < 16) return emptyList()

        val croppedBitmap = Bitmap.createBitmap(
            fullBitmap,
            cropRect.left,
            cropRect.top,
            cropW,
            cropH
        )
        return try {
            recognizeCroppedBitmap(
                cropped = croppedBitmap,
                offsetX = cropRect.left,
                offsetY = cropRect.top,
                excludeRect = excludeRect
            )
        } finally {
            if (croppedBitmap !== fullBitmap && !croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }
        }
    }

    /**
     * 一步完成：静默截屏 + 局部小区域裁剪识别 + 自动释放内存
     */
    suspend fun captureAndRecognizeRegion(
        service: AccessibilityService,
        region: PctRegion,
        excludeRect: Rect? = null
    ): List<OcrLineRecord> {
        val full = captureScreenBitmap(service) ?: return emptyList()
        return try {
            recognizeInRegion(full, region, excludeRect)
        } finally {
            if (!full.isRecycled) {
                full.recycle()
            }
        }
    }

    private suspend fun takeSilentScreenshotOnce(service: AccessibilityService): Bitmap? =
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
                                AppLogBus.warn("截屏缓冲转换异常：${t.message}")
                                if (cont.isActive) cont.resume(null)
                            } finally {
                                hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                AppLogBus.warn("截屏未成功(code=$errorCode)")
                            }
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private suspend fun recognizeCroppedBitmap(
        cropped: Bitmap,
        offsetX: Int,
        offsetY: Int,
        excludeRect: Rect?
    ): List<OcrLineRecord> = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(cropped, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val records = ArrayList<OcrLineRecord>(16)
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val absRect = Rect(
                            box.left + offsetX,
                            box.top + offsetY,
                            box.right + offsetX,
                            box.bottom + offsetY
                        )
                        if (excludeRect != null && Rect.intersects(absRect, excludeRect)) {
                            continue
                        }
                        val raw = line.text.trim()
                        val norm = normalizeText(raw)
                        if (norm.isEmpty()) continue
                        records.add(
                            OcrLineRecord(
                                text = raw,
                                normalizedText = norm,
                                bounds = absRect
                            )
                        )
                    }
                }
                if (cont.isActive) cont.resume(records)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    fun findMatchInLines(
        lines: List<OcrLineRecord>,
        keywords: List<String>,
        excludeContaining: List<String> = emptyList()
    ): TargetMatch? {
        for (keyword in keywords) {
            val cleanKey = normalizeText(keyword)
            if (cleanKey.isEmpty()) continue

            for (line in lines) {
                if (excludeContaining.any { ex -> line.normalizedText.contains(normalizeText(ex)) }) {
                    continue
                }
                if (cleanKey.length == 1 && line.normalizedText.length > 3) {
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
        private val DECOR_EDGE_REGEX =
            Regex("^[×xX✕✖·•|丨\\[\\]【】()（）<>《》\\s]+|[×xX✕✖·•|丨\\[\\]【】()（）<>《》\\s]+$")

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
                .replace("兔费", "免费")
                .replace("堤前", "提前")
        }
    }
}
