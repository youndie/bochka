package io.github.youndie.bochka.http.nio

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns NIO readiness into something a coroutine can await.
 *
 * This exists because of decision Р3. Ktor's socket API hands out a `ByteChannel` and keeps the
 * `SocketChannel` to itself, and `FileChannel.transferTo` needs the real channel — so a server that
 * wants `sendfile` has to own its own readiness engine. This is that engine, and it is deliberately
 * the smallest one that works: register interest, wait, resume.
 *
 * ## Why interest changes are queued
 *
 * `SelectionKey.interestOps` from another thread while `select()` is in progress is, depending on
 * the platform, either blocking or silently ineffective. Every change is therefore posted to a
 * queue and applied by the loop thread itself, which then means the loop has to be woken — hence
 * the [Selector.wakeup] after each post. Skipping that wakeup does not fail; it hangs, once, under
 * load, in a way that looks like a slow client.
 */
class SelectorLoop : Closeable {
    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    private val closed = AtomicBoolean(false)

    private val thread =
        Thread({ run() }, "bochka-selector").apply {
            isDaemon = true
            start()
        }

    /** Registers [channel] with no interest yet. The key is the handle used by the await calls. */
    fun register(channel: SelectableChannel): SelectionKey {
        channel.configureBlocking(false)
        // Registration also has to happen on the loop thread: `register` blocks against a
        // concurrent `select()` on some JDK implementations, which is a deadlock rather than a
        // delay. Posting it and waiting for the answer keeps the rule "only the loop touches the
        // selector" without exceptions.
        val slot = arrayOfNulls<SelectionKey>(1)
        val done = java.util.concurrent.CountDownLatch(1)
        post {
            slot[0] = channel.register(selector, 0)
            done.countDown()
        }
        done.await()
        return slot[0]!!
    }

    /** Suspends until [key]'s channel can be read from. */
    suspend fun awaitReadable(key: SelectionKey) = await(key, SelectionKey.OP_READ)

    /** Suspends until [key]'s channel can be written to. */
    suspend fun awaitWritable(key: SelectionKey) = await(key, SelectionKey.OP_WRITE)

    /** Suspends until [key]'s channel has a connection to accept. */
    suspend fun awaitAcceptable(key: SelectionKey) = await(key, SelectionKey.OP_ACCEPT)

    private suspend fun await(
        key: SelectionKey,
        interest: Int,
    ) = suspendCancellableCoroutine { continuation ->
        post {
            if (!key.isValid) {
                continuation.resumeWithException(java.nio.channels.ClosedChannelException())
                return@post
            }
            // One waiter per key at a time — the session loop is sequential, so there is never a
            // second one. Attaching rather than keeping a map means the lookup on the ready path is
            // a field read.
            key.attach(Waiter(interest, continuation))
            key.interestOps(key.interestOps() or interest)
        }
        continuation.invokeOnCancellation {
            post {
                if (key.isValid) {
                    key.attach(null)
                    key.interestOps(key.interestOps() and interest.inv())
                }
            }
        }
    }

    private fun post(action: () -> Unit) {
        pending.add(action)
        selector.wakeup()
    }

    private fun run() {
        while (!closed.get()) {
            try {
                drainPending()
                if (closed.get()) break
                selector.select()
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    resumeIfReady(key)
                }
            } catch (_: java.nio.channels.ClosedSelectorException) {
                break
            } catch (e: Throwable) {
                // A single misbehaving key must not take the loop down with it, because the loop is
                // every connection on this server. Its own waiter already got the exception.
                if (closed.get()) break
                failAllWaiters(e)
            }
        }
        failAllWaiters(java.nio.channels.ClosedSelectorException())
    }

    private fun drainPending() {
        while (true) {
            val action = pending.poll() ?: return
            try {
                action()
            } catch (_: CancelledKeyException) {
                // The channel closed between posting and running. The waiter, if any, is resumed
                // when its key is swept below.
            }
        }
    }

    private fun resumeIfReady(key: SelectionKey) {
        val waiter = key.attachment() as? Waiter ?: return
        if (key.readyOps() and waiter.interest == 0) return
        key.attach(null)
        // Interest is cleared before resuming. Left set, a level-triggered selector would spin at
        // full speed on a channel nobody is waiting for any more — which looks like a busy broker
        // and is an idle one burning a core.
        key.interestOps(key.interestOps() and waiter.interest.inv())
        waiter.continuation.resume(Unit)
    }

    private fun failAllWaiters(cause: Throwable) {
        for (key in selector.keys()) {
            val waiter = key.attachment() as? Waiter ?: continue
            key.attach(null)
            waiter.continuation.resumeWithException(cause)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        selector.wakeup()
        thread.join(CLOSE_TIMEOUT_MILLIS)
        selector.close()
    }

    private class Waiter(
        val interest: Int,
        val continuation: CancellableContinuation<Unit>,
    )

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}
