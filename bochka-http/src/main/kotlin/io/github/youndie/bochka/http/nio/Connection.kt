package io.github.youndie.bochka.http.nio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel

/**
 * One client connection, as the session loop sees it.
 *
 * `readSome` and not `readFully`: HTTP is not framed, so the session reads whatever has arrived and
 * feeds it to a parser that knows where things end. A `readFully` shape would need the session to
 * know the length of something before reading it, which is exactly what it is trying to find out.
 */
interface Connection : Closeable {
    /**
     * The channel `FileChannel.transferTo` will be handed in M-59.
     *
     * Exposed so the property can be checked rather than assumed: the JDK takes the `sendfile` path
     * **only** when the target is a `SocketChannelImpl`, and wrapping the socket in any decorator
     * turns the read path into a copy loop that returns identical bytes (research, §1.6.3).
     */
    val transferTarget: WritableByteChannel

    /** Reads whatever is available into [buffer]; returns -1 when the peer is done. */
    suspend fun readSome(buffer: ByteBuffer): Int

    suspend fun writeFully(buffer: ByteBuffer)

    /**
     * Suspends until the socket will take more.
     *
     * Needed by the one writer that does not go through [writeFully]: `transferTo` writes to the
     * channel itself, so when it returns zero there is no buffer to retry with — only a socket to
     * wait on.
     */
    suspend fun awaitWritable()
}

/** Non-blocking transport: readiness comes from [SelectorLoop], the coroutine suspends meanwhile. */
class SelectorConnection(
    private val channel: SocketChannel,
    private val key: SelectionKey,
    private val loop: SelectorLoop,
) : Connection {
    override val transferTarget: WritableByteChannel get() = channel

    override suspend fun readSome(buffer: ByteBuffer): Int {
        while (true) {
            val read = channel.read(buffer)
            if (read != 0) return read
            loop.awaitReadable(key)
        }
    }

    override suspend fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            // Zero from a non-blocking write means the send buffer is full, not that the peer has
            // gone. Treating it as either is a bug with its own symptom: a truncated response, or a
            // core at 100 % that looks like a busy server (research, §1.4 of the broker).
            if (channel.write(buffer) == 0) loop.awaitWritable(key)
        }
    }

    override suspend fun awaitWritable() = loop.awaitWritable(key)

    override fun close() {
        key.cancel()
        channel.close()
    }
}
