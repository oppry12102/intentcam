package com.example.intentcam

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Await a GMS [Task] with cancellation awareness.
 *
 * The GMS [Task] API does not expose a `cancel()` method, so the underlying
 * work cannot be aborted.  What this helper does is:
 *
 * - Suspends the calling coroutine until the task fires either its success or
 *   failure listener.
 * - When the calling coroutine is cancelled, returns cleanly without
 *   throwing [IllegalStateException] for resuming a dead continuation.
 * - The GMS task is allowed to complete in the background; its result is
 *   simply discarded, and [CancellationException] is what structured
 *   concurrency propagates up to the caller.
 *
 * Both branches ([addOnSuccessListener] / [addOnFailureListener]) guard on
 * `cont.isCancelled` to avoid the IllegalStateException that `resume` /
 * `resumeWithException` throws when invoked on an already-cancelled
 * continuation.  An [AtomicBoolean] prevents double-resume in the rare race
 * where success and failure both arrive.
 */
internal suspend fun <T> Task<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { cont ->
        val settled = AtomicBoolean(false)
        addOnSuccessListener { result ->
            if (!settled.compareAndSet(false, true)) return@addOnSuccessListener
            if (cont.isCancelled) return@addOnSuccessListener
            cont.resume(result)
        }
        addOnFailureListener { error ->
            if (!settled.compareAndSet(false, true)) return@addOnFailureListener
            if (cont.isCancelled) return@addOnFailureListener
            cont.resumeWithException(error)
        }
    }
