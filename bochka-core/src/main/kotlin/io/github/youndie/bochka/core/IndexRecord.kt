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
        val metadata: Metadata,
    ) : IndexRecord

    data class Deleted(
        override val bucket: String,
        val key: ObjectKey,
    ) : IndexRecord

    /**
     * A multipart upload that has begun.
     *
     * In the log rather than only in memory because such an upload runs for minutes: a client has
     * been told its parts were accepted, and a restart that forgot them would make that a lie.
     */
    data class UploadStarted(
        override val bucket: String,
        val key: ObjectKey,
        val uploadId: String,
        val startedAtMillis: Long,
        val metadata: Metadata,
    ) : IndexRecord

    data class UploadPart(
        override val bucket: String,
        val uploadId: String,
        val number: Int,
        val fileId: String,
        val size: Long,
        val eTag: String,
        val lastModifiedMillis: Long,
    ) : IndexRecord

    /** Completed or aborted — from the index's side those are the same event: the upload is over. */
    data class UploadEnded(
        override val bucket: String,
        val uploadId: String,
    ) : IndexRecord

    companion object {
        private const val KIND_BUCKET_CREATED: Byte = 1
        private const val KIND_BUCKET_DELETED: Byte = 2

        /**
         * A put that carried nothing but a content type — everything written before M-46.
         *
         * Kept readable rather than removed, and the reason is what the log is: a store that
         * cannot open the log it wrote last week is a store that loses data on upgrade. A new
         * field gets a new kind byte, an old kind keeps decoding to what it meant.
         */
        private const val KIND_PUT_CONTENT_TYPE_ONLY: Byte = 3
        private const val KIND_DELETED: Byte = 4
        private const val KIND_PUT: Byte = 5
        private const val KIND_UPLOAD_STARTED: Byte = 6
        private const val KIND_UPLOAD_PART: Byte = 7
        private const val KIND_UPLOAD_ENDED: Byte = 8

        /**
         * A ceiling on the entry count read back from a record, so a corrupt length allocates
         * nothing. AWS caps user metadata at 2 KiB of names and values together; 256 entries is
         * well past what fits and well short of what an accident produces.
         */
        private const val MAX_USER_METADATA = 256L

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

                is UploadStarted -> {
                    out.write(KIND_UPLOAD_STARTED.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.key.toByteArray())
                    out.putField(record.uploadId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.startedAtMillis)
                    out.putMetadata(record.metadata)
                }

                is UploadPart -> {
                    out.write(KIND_UPLOAD_PART.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.uploadId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.number.toLong())
                    out.putField(record.fileId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.size)
                    out.putField(record.eTag.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.lastModifiedMillis)
                }

                is UploadEnded -> {
                    out.write(KIND_UPLOAD_ENDED.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.uploadId.toByteArray(StandardCharsets.US_ASCII))
                }

                is Put -> {
                    out.write(KIND_PUT.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.key.toByteArray())
                    out.putField(record.fileId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.size)
                    out.putInt64(record.lastModifiedMillis)
                    out.putField(record.eTag.toByteArray(StandardCharsets.US_ASCII))
                    out.putMetadata(record.metadata)
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

                KIND_PUT_CONTENT_TYPE_ONLY -> {
                    Put(
                        bucket = buffer.text(),
                        key = ObjectKey(buffer.bytes()),
                        fileId = buffer.text(),
                        size = buffer.long,
                        lastModifiedMillis = buffer.long,
                        eTag = buffer.text(),
                        metadata = Metadata(contentType = buffer.text().ifEmpty { null }),
                    )
                }

                KIND_UPLOAD_STARTED -> {
                    UploadStarted(
                        bucket = buffer.text(),
                        key = ObjectKey(buffer.bytes()),
                        uploadId = buffer.text(),
                        startedAtMillis = buffer.long,
                        metadata = buffer.metadata(),
                    )
                }

                KIND_UPLOAD_PART -> {
                    UploadPart(
                        bucket = buffer.text(),
                        uploadId = buffer.text(),
                        number = buffer.long.toInt(),
                        fileId = buffer.text(),
                        size = buffer.long,
                        eTag = buffer.text(),
                        lastModifiedMillis = buffer.long,
                    )
                }

                KIND_UPLOAD_ENDED -> {
                    UploadEnded(buffer.text(), buffer.text())
                }

                KIND_PUT -> {
                    val bucket = buffer.text()
                    val key = ObjectKey(buffer.bytes())
                    val fileId = buffer.text()
                    val size = buffer.long
                    val lastModified = buffer.long
                    val eTag = buffer.text()
                    Put(
                        bucket = bucket,
                        key = key,
                        fileId = fileId,
                        size = size,
                        lastModifiedMillis = lastModified,
                        eTag = eTag,
                        metadata = buffer.metadata(),
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

        private fun ByteArrayOutputStream.putMetadata(metadata: Metadata) {
            with(metadata) {
                putText(contentType)
                putText(cacheControl)
                putText(contentDisposition)
                putText(contentEncoding)
                putText(contentLanguage)
                putText(expires)
                putText(checksum?.algorithm)
                putText(checksum?.value)
                putInt64(user.size.toLong())
                for ((name, value) in user) {
                    putField(name.toByteArray(StandardCharsets.UTF_8))
                    putField(value.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }

        /**
         * The fields are read into locals before the constructor call on purpose: what fixes the
         * order of a decode is the order the calls are written, and a named-argument list is a
         * place where that order is easy to change by accident.
         */
        private fun ByteBuffer.metadata(): Metadata {
            val contentType = optionalText()
            val cacheControl = optionalText()
            val disposition = optionalText()
            val encoding = optionalText()
            val language = optionalText()
            val expires = optionalText()
            val algorithm = optionalText()
            val checksum = optionalText()
            val userCount = long
            require(userCount in 0..MAX_USER_METADATA) { "index record claims $userCount metadata entries" }
            val user = LinkedHashMap<String, String>()
            repeat(userCount.toInt()) { user[text()] = text() }
            return Metadata(
                contentType = contentType,
                cacheControl = cacheControl,
                contentDisposition = disposition,
                contentEncoding = encoding,
                contentLanguage = language,
                expires = expires,
                user = user,
                checksum = if (algorithm != null && checksum != null) Metadata.Checksum(algorithm, checksum) else null,
            )
        }

        /**
         * An optional field: one flag byte, then the field if there is one.
         *
         * A flag rather than "empty means absent", because `x-amz-meta-x:` with nothing after the
         * colon is a value S3 keeps, and folding it into absence would make a round trip through
         * the log lose it.
         */
        private fun ByteArrayOutputStream.putText(value: String?) {
            if (value == null) {
                write(0)
            } else {
                write(1)
                putField(value.toByteArray(StandardCharsets.UTF_8))
            }
        }

        private fun ByteBuffer.optionalText(): String? = if (get().toInt() == 0) null else text()

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
