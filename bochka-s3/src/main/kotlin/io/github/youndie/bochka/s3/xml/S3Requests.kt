package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import java.nio.charset.StandardCharsets

/**
 * The two request bodies S3 takes as XML.
 *
 * Both are bounded by the protocol — a batch delete carries at most 1000 keys, a completion at most
 * 10 000 parts — so they are read whole. The bound is enforced here rather than assumed: this is an
 * unauthenticated-shaped input path, and "the client would not send more" is not a property of the
 * client (Риск 3).
 */
object S3Requests {
    /** `shapes.Delete.members`: `Objects` is flattened as `Object`, plus an optional `Quiet`. */
    data class DeleteRequest(
        val keys: List<ObjectKey>,
        val quiet: Boolean,
    )

    /** `shapes.CompletedMultipartUpload.members`: `Parts` flattened as `Part`. */
    data class CompletedPart(
        val partNumber: Int,
        val eTag: String,
    )

    /** A batch delete takes at most 1000 objects per request. */
    const val MAX_DELETE_KEYS: Int = 1000

    /** Part numbers run 1..10 000 (`docs/spec/s3-service-2.json:1604`), so a list cannot be longer. */
    const val MAX_PARTS: Int = 10_000

    fun parseDelete(body: ByteArray): DeleteRequest {
        val keys = ArrayList<ObjectKey>()
        var quiet = false
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))

        reader.root("Delete") { name ->
            when (name) {
                "Object" -> {
                    var key: ObjectKey? = null
                    reader.children { field ->
                        // VersionId is read and dropped: versioning is out of scope, and refusing a
                        // member the client is allowed to send would break it for no gain.
                        if (field == "Key") key = ObjectKey(reader.textOf(field).toByteArray())
                    }
                    val parsed = key ?: throw XmlReader.MalformedXmlException("<Object> without <Key>")
                    if (keys.size >= MAX_DELETE_KEYS) {
                        throw XmlReader.MalformedXmlException("more than $MAX_DELETE_KEYS objects in one delete")
                    }
                    keys.add(parsed)
                }

                "Quiet" -> {
                    quiet = reader.textOf(name).trim().equals("true", ignoreCase = true)
                }
            }
        }
        return DeleteRequest(keys, quiet)
    }

    /**
     * The part list of `CompleteMultipartUpload`.
     *
     * Order is **not** checked here. Parts must arrive ascending by number, and out of order is
     * `InvalidPartOrder` — but that is a 400 with a code, not a malformed document, and the
     * difference is what the client sees. Checked where the upload is completed (M-55).
     */
    fun parseCompleteMultipartUpload(body: ByteArray): List<CompletedPart> {
        val parts = ArrayList<CompletedPart>()
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))

        reader.root("CompleteMultipartUpload") { name ->
            if (name != "Part") return@root
            var number: Int? = null
            var eTag: String? = null
            reader.children { field ->
                when (field) {
                    "PartNumber" -> {
                        val raw = reader.textOf(field).trim()
                        number =
                            raw.toIntOrNull()
                                ?: throw XmlReader.MalformedXmlException("<PartNumber> is not a number: '$raw'")
                    }

                    // Quotes are kept exactly as they arrived: an ETag is compared verbatim, and
                    // stripping them here would make it not match what was handed out.
                    "ETag" -> {
                        eTag = reader.textOf(field).trim()
                    }
                }
            }
            val n = number ?: throw XmlReader.MalformedXmlException("<Part> without <PartNumber>")
            val tag = eTag ?: throw XmlReader.MalformedXmlException("<Part> without <ETag>")
            if (parts.size >= MAX_PARTS) {
                throw XmlReader.MalformedXmlException("more than $MAX_PARTS parts in one completion")
            }
            parts.add(CompletedPart(n, tag))
        }
        return parts
    }
}
