package io.github.youndie.bochka.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * What one line of the index log says.
 *
 * A binary encoding rather than text, for one reason that is not performance: an object key is a
 * byte string that need not be valid UTF-8 (Р3), so any format with a delimiter or an escape has
 * to answer what a key containing that delimiter does. Length-prefixed fields do not have the
 * question.
 */
sealed interface IndexRecord {
    val bucket: String

    data class BucketCreated(
        override val bucket: String,
    ) : IndexRecord

    data class BucketDeleted(
        override val bucket: String,
    ) : IndexRecord

    data class Put(
        override val bucket: String,
        val key: ObjectKey,
        val fileId: String,
        val size: Long,
        val eTag: String,
        val lastModifiedMillis: Long,
        val contentType: String?,
    ) : IndexRecord

    data class Deleted(
        override val bucket: String,
        val key: ObjectKey,
    ) : IndexRecord

    companion object {
        private const val KIND_BUCKET_CREATED: Byte = 1
        private const val KIND_BUCKET_DELETED: Byte = 2
        private const val KIND_PUT: Byte = 3
        private const val KIND_DELETED: Byte = 4

        fun encode(record: IndexRecord): ByteArray {
            val out = ByteArrayOutputStream(128)
            when (record) {
                is BucketCreated -> {
                    out.write(KIND_BUCKET_CREATED.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                }

                is BucketDeleted -> {
                    out.write(KIND_BUCKET_DELETED.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                }

                is Deleted -> {
                    out.write(KIND_DELETED.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.key.toByteArray())
                }

                is Put -> {
                    out.write(KIND_PUT.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.key.toByteArray())
                    out.putField(record.fileId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.size)
                    out.putInt64(record.lastModifiedMillis)
                    out.putField(record.eTag.toByteArray(StandardCharsets.US_ASCII))
                    out.putField((record.contentType ?: "").toByteArray(StandardCharsets.UTF_8))
                }
            }
            return out.toByteArray()
        }

        fun decode(payload: ByteArray): IndexRecord {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            return when (val kind = buffer.get()) {
                KIND_BUCKET_CREATED -> {
                    BucketCreated(buffer.text())
                }

                KIND_BUCKET_DELETED -> {
                    BucketDeleted(buffer.text())
                }

                KIND_DELETED -> {
                    Deleted(buffer.text(), ObjectKey(buffer.bytes()))
                }

                KIND_PUT -> {
                    Put(
                        bucket = buffer.text(),
                        key = ObjectKey(buffer.bytes()),
                        fileId = buffer.text(),
                        size = buffer.long,
                        lastModifiedMillis = buffer.long,
                        eTag = buffer.text(),
                        contentType = buffer.text().ifEmpty { null },
                    )
                }

                else -> {
                    throw IllegalArgumentException("unknown index record kind $kind")
                }
            }
        }

        /**
         * A length-prefixed field.
         *
         * Named `putField` and not `writeBytes`, which is what it was called first: the JDK has had
         * `ByteArrayOutputStream.writeBytes(byte[])` since 11, a member wins over an extension in
         * Kotlin, and so every call silently wrote the payload **without its length**. Nothing
         * complained — not the compiler, not the writer, not the log — and it surfaced as a decoder
         * reading four bytes of a bucket name as a field length of 1.8 billion.
         */
        private fun ByteArrayOutputStream.putField(value: ByteArray) {
            val length =
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(value.size)
                    .array()
            write(length)
            write(value)
        }

        private fun ByteArrayOutputStream.putInt64(value: Long) {
            write(
                ByteBuffer
                    .allocate(8)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(value)
                    .array(),
            )
        }

        private fun ByteBuffer.bytes(): ByteArray {
            val length = int
            require(length >= 0 && length <= remaining()) { "index record claims a field of $length bytes" }
            val out = ByteArray(length)
            get(out)
            return out
        }

        private fun ByteBuffer.text(): String = String(bytes(), StandardCharsets.UTF_8)
    }
}
