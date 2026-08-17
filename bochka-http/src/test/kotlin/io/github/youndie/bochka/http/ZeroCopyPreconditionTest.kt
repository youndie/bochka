package io.github.youndie.bochka.http

import io.github.youndie.bochka.http.nio.SelectorConnection
import io.github.youndie.bochka.http.nio.SelectorLoop
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M-60: the object `transferTo` is handed has to be one the JDK will take the fast path for.
 *
 * This is a gate on a **precondition**, not on a result, because the result is identical either
 * way. `FileChannelImpl.transferTo` checks whether its target implements the internal interface
 * `sun.nio.ch.SelChImpl`; when it does, the bytes go from the page cache to the socket inside the
 * kernel, and when it does not, the JDK quietly falls back to a loop through an 8 KiB heap buffer.
 * The response is byte-for-byte the same, the tests pass, the clients are happy, and the
 * measurement in M-61 says that fallback costs **five times** the processor per byte.
 *
 * So the thing that must not change is that nothing wraps the socket. The day somebody adds a
 * decorator — for logging, for metrics, for anything — this test is what says so, rather than a
 * gradual increase in processor time that nobody attributes to it.
 */
class ZeroCopyPreconditionTest {
    @Test
    fun `the connection hands transferTo the socket itself, not a wrapper`() {
        SelectorLoop().use { loop ->
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                SocketChannel.open(server.localAddress as InetSocketAddress).use { client ->
                    server.accept().use { accepted ->
                        accepted.configureBlocking(false)
                        val key = loop.register(accepted)
                        val connection = SelectorConnection(accepted, key, loop)

                        // Identity, not just type: a decorator that also implements SocketChannel
                        // would satisfy an `is` check and still lose the fast path.
                        assertSame(
                            accepted,
                            connection.transferTarget,
                            "the connection must expose the socket channel itself",
                        )
                        client.close()
                    }
                }
            }
        }
    }

    @Test
    fun `the socket satisfies the condition the JDK actually checks`() {
        // Not "it is a SocketChannel" — that is the check that looks right and is not the one the
        // JDK makes. `FileChannelImpl.transferTo` asks whether the target is a `SelChImpl`, so
        // that is what is asserted, by name, against the running JDK rather than against a memory
        // of what it does.
        val fastPath = Class.forName("sun.nio.ch.SelChImpl")
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            SocketChannel.open(server.localAddress as InetSocketAddress).use { client ->
                server.accept().use { accepted ->
                    assertTrue(
                        fastPath.isInstance(accepted),
                        "an accepted socket is a ${accepted.javaClass.name}, which transferTo will not sendfile to",
                    )
                }
                assertTrue(fastPath.isInstance(client))
            }
        }
    }
}
