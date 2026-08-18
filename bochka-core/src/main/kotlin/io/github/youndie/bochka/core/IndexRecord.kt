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
        /**
         * When, so a listing can say. Zero for a bucket recorded before this field existed — the
         * honest reading of a log that never wrote it, and the alternative is worse: filling in
         * the time of the restart makes every bucket look newly created, and look different again
         * after the next one.
         */
        val createdAtMillis: Long = 0,
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
        /** Empty for an ordinary upload; the seams of an assembled one (M-82, M-83). */
        val parts: List<ObjectStore.PartSummary> = emptyList(),
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
        /**
         * What the client said the parts would be checksummed with, and how they combine.
         *
         * Both are S3's words carried verbatim — `crc32c`, `FULL_OBJECT` — because the choice is
         * made once, on the request that starts the upload, and every later part and the
         * completion have to agree with it. Forgetting it across a restart would answer a
         * different question than the client asked: a `FULL_OBJECT` upload whose completion
         * defaulted back to `COMPOSITE` hands out a value that describes nothing the client can
         * check, and does it silently.
         */
        val checksumAlgorithm: String? = null,
        val checksumType: String? = null,
    ) : IndexRecord

    data class UploadPart(
        override val bucket: String,
        val uploadId: String,
        val number: Int,
        val fileId: String,
        val size: Long,
        val eTag: String,
        val lastModifiedMillis: Long,
        /**
         * What the client stated about this part.
         *
         * In the log for the same reason as the part itself: the object's checksum is computed
         * from these at completion, so an upload that survives a restart with its parts but
         * without their checksums completes into an object whose checksum is missing — or, worse,
         * computed from the subset that happened to be in memory.
         */
        val checksum: Metadata.Checksum? = null,
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
         * A put that also remembers the parts it was assembled from.
         *
         * A new kind byte for a new field, which is the rule this file already follows for
         * [KIND_PUT_CONTENT_TYPE_ONLY]: an old kind keeps decoding to exactly what it meant, so a
         * log written last week opens without anybody having to reason about which fields a record
         * of that vintage carries.
         */
        private const val KIND_PUT_WITH_PARTS: Byte = 9

        /**
         * The two upload records, once they carry checksums.
         *
         * Same rule again, third time: a new field is a new kind byte, and 6 and 7 keep decoding
         * to exactly what they meant — an upload with no checksum stated and a part with none.
         * That is the correct reading of an old record rather than a convenient one; those uploads
         * really did carry no checksum.
         */
        private const val KIND_UPLOAD_STARTED_WITH_CHECKSUM: Byte = 10
        private const val KIND_UPLOAD_PART_WITH_CHECKSUM: Byte = 11

        /** A bucket that also remembers when it was created. Kind 1 decodes to "not recorded". */
        private const val KIND_BUCKET_CREATED_AT: Byte = 12

        /** Part numbers run 1..10 000, so a record claiming more than that is corrupt. */
        private const val MAX_PARTS = 10_000L

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
                    out.write(KIND_BUCKET_CREATED_AT.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putInt64(record.createdAtMillis)
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
                    out.write(KIND_UPLOAD_STARTED_WITH_CHECKSUM.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.key.toByteArray())
                    out.putField(record.uploadId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.startedAtMillis)
                    out.putMetadata(record.metadata)
                    out.putText(record.checksumAlgorithm)
                    out.putText(record.checksumType)
                }

                is UploadPart -> {
                    out.write(KIND_UPLOAD_PART_WITH_CHECKSUM.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.uploadId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.number.toLong())
                    out.putField(record.fileId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.size)
                    out.putField(record.eTag.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.lastModifiedMillis)
                    out.putText(record.checksum?.algorithm)
                    out.putText(record.checksum?.value)
                }

                is UploadEnded -> {
                    out.write(KIND_UPLOAD_ENDED.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.uploadId.toByteArray(StandardCharsets.US_ASCII))
                }

                is Put -> {
                    out.write(KIND_PUT_WITH_PARTS.toInt())
                    out.putField(record.bucket.toByteArray(StandardCharsets.UTF_8))
                    out.putField(record.key.toByteArray())
                    out.putField(record.fileId.toByteArray(StandardCharsets.US_ASCII))
                    out.putInt64(record.size)
                    out.putInt64(record.lastModifiedMillis)
                    out.putField(record.eTag.toByteArray(StandardCharsets.US_ASCII))
                    out.putMetadata(record.metadata)
                    out.putInt64(record.parts.size.toLong())
                    for (part in record.parts) {
                        out.putInt64(part.number.toLong())
                        out.putInt64(part.size)
                        out.putField(part.eTag.toByteArray(StandardCharsets.US_ASCII))
                        out.putText(part.checksum?.algorithm)
                        out.putText(part.checksum?.value)
                    }
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

                KIND_BUCKET_CREATED_AT -> {
                    BucketCreated(buffer.text(), buffer.long)
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

                KIND_UPLOAD_STARTED, KIND_UPLOAD_STARTED_WITH_CHECKSUM -> {
                    val bucket = buffer.text()
                    val key = ObjectKey(buffer.bytes())
                    val uploadId = buffer.text()
                    val startedAt = buffer.long
                    val metadata = buffer.metadata()
                    UploadStarted(
                        bucket = bucket,
                        key = key,
                        uploadId = uploadId,
                        startedAtMillis = startedAt,
                        metadata = metadata,
                        checksumAlgorithm =
                            if (kind ==
                                KIND_UPLOAD_STARTED_WITH_CHECKSUM
                            ) {
                                buffer.optionalText()
                            } else {
                                null
                            },
                        checksumType = if (kind == KIND_UPLOAD_STARTED_WITH_CHECKSUM) buffer.optionalText() else null,
                    )
                }

                KIND_UPLOAD_PART, KIND_UPLOAD_PART_WITH_CHECKSUM -> {
                    val bucket = buffer.text()
                    val uploadId = buffer.text()
                    val number = buffer.long.toInt()
                    val fileId = buffer.text()
                    val size = buffer.long
                    val eTag = buffer.text()
                    val lastModified = buffer.long
                    val algorithm = if (kind == KIND_UPLOAD_PART_WITH_CHECKSUM) buffer.optionalText() else null
                    val value = if (kind == KIND_UPLOAD_PART_WITH_CHECKSUM) buffer.optionalText() else null
                    UploadPart(
                        bucket = bucket,
                        uploadId = uploadId,
                        number = number,
                        fileId = fileId,
                        size = size,
                        eTag = eTag,
                        lastModifiedMillis = lastModified,
                        checksum =
                            if (algorithm != null &&
                                value != null
                            ) {
                                Metadata.Checksum(algorithm, value)
                            } else {
                                null
                            },
                    )
                }

                KIND_UPLOAD_ENDED -> {
                    UploadEnded(buffer.text(), buffer.text())
                }

                KIND_PUT, KIND_PUT_WITH_PARTS -> {
                    val bucket = buffer.text()
                    val key = ObjectKey(buffer.bytes())
                    val fileId = buffer.text()
                    val size = buffer.long
                    val lastModified = buffer.long
                    val eTag = buffer.text()
                    val metadata = buffer.metadata()
                    val parts = if (kind == KIND_PUT_WITH_PARTS) buffer.parts() else emptyList()
                    Put(
                        bucket = bucket,
                        key = key,
                        fileId = fileId,
                        size = size,
                        lastModifiedMillis = lastModified,
                        eTag = eTag,
                        metadata = metadata,
                        parts = parts,
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

        /**
         * Metadata, and every string in it is a **byte string** rather than text.
         *
         * These came off the wire as HTTP header values, which the parser widens one byte to one
         * char, and they go back out the same way. So they are written with ISO-8859-1, which is
         * that widening in reverse and loses nothing. Writing them as UTF-8 would double every
         * byte above 0x7F on the way in, and the object would come back with a value it was never
         * given — `Hello WorldÃ©` for `Hello Worldé`, which is the shape of every mojibake anybody
         * has ever debugged.
         */
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
                    putField(name.toByteArray(StandardCharsets.ISO_8859_1))
                    putField(value.toByteArray(StandardCharsets.ISO_8859_1))
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
            repeat(userCount.toInt()) { user[latin1()] = latin1() }
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
                putField(value.toByteArray(StandardCharsets.ISO_8859_1))
            }
        }

        private fun ByteBuffer.optionalText(): String? = if (get().toInt() == 0) null else latin1()

        private fun ByteBuffer.parts(): List<ObjectStore.PartSummary> {
            val count = long
            require(count in 0..MAX_PARTS) { "index record claims $count parts" }
            return List(count.toInt()) {
                val number = long.toInt()
                val size = long
                val eTag = text()
                val algorithm = optionalText()
                val value = optionalText()
                ObjectStore.PartSummary(
                    number = number,
                    size = size,
                    eTag = eTag,
                    checksum = if (algorithm != null && value != null) Metadata.Checksum(algorithm, value) else null,
                )
            }
        }

        /** The byte-preserving read, for fields that were header values rather than text. */
        private fun ByteBuffer.latin1(): String = String(bytes(), StandardCharsets.ISO_8859_1)

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
