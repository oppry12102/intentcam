package com.example.intentcam

/**
 * Format a [Throwable] for the in-app debug log: top-level class+message,
 * followed by the cause chain (one level is usually enough), followed by
 * the first few stack frames.  Newlines are stripped so the result still
 * fits one [DebugLogEntry] row.  DEBUG mode is for hunting crashes; the
 * caller wants to see WHERE it threw, not just WHAT.
 *
 * Example:
 *   IllegalStateException: streamToolUse: 模型 20000ms 内未完成
 *     caused by kotlinx.coroutines.TimeoutCancellationException
 *     at com.example.intentcam.LlmClient.streamToolUseBody$lambda...
 */
fun formatThrowable(t: Throwable): String {
    val sb = StringBuilder()
    sb.append(t.javaClass.simpleName)
    sb.append(": ")
    sb.append(t.message ?: "(no message)")
    var cause: Throwable? = t.cause
    var depth = 0
    while (cause != null && depth < 3) {
        sb.append(" | caused by ")
        sb.append(cause.javaClass.simpleName)
        sb.append(": ")
        sb.append(cause.message ?: "(no message)")
        cause = cause.cause
        depth++
    }
    // Top 3 frames are usually enough to identify the failing call site
    // without dumping hundreds of frames of androidx / kotlin coroutine
    // machinery into the panel.
    t.stackTrace.take(3).forEach { f ->
        sb.append(" | at ")
        sb.append(f.className).append('.').append(f.methodName)
        sb.append('(').append(f.fileName ?: "?").append(':').append(f.lineNumber).append(')')
    }
    return sb.toString()
}