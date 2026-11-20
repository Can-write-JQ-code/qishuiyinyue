package io.github.jiaqi.qishuihelper.util

import android.util.Log
import io.github.jiaqi.qishuihelper.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object AppLogBus {

    private const val TAG = "QishuiAdHelper"
    private const val MAX_LOG_LINES = 160
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow(TaskStatus())
    val status: StateFlow<TaskStatus> = _status.asStateFlow()

    fun info(message: String) {
        val line = "[${LocalTime.now().format(timeFormatter)}] $message"
        Log.i(TAG, message)
        _logs.update { current ->
            val next = current + line
            if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
    }

    fun warn(message: String) {
        val line = "[${LocalTime.now().format(timeFormatter)}] ⚠️ $message"
        Log.w(TAG, message)
        _logs.update { current ->
            val next = current + line
            if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
    }

    fun updateStatus(status: TaskStatus) {
        _status.value = status
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
