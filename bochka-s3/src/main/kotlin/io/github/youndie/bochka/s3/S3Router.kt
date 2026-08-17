package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey

/**
 * Works out which S3 operation a request is.
 *
 * Two things make this less obvious than a routing table. The bucket may be in the path or in the
 * `Host` header, and the operation is often decided by a **query parameter that carries no value**
 * — `?uploads`, `?delete`, `?location` — so `POST /photos/big.bin?uploads` and
 * `POST /photos/big.bin?uploadId=x` are different operations on the same method and path.
 *
 * Which style a request uses is decided by configuration, never guessed. [virtualHostSuffixes] is
 * the list of domains under which a leading label is a bucket name; anything else is path-style.
 * Auto-detection ("looks like an AWS domain, so virtual-hosted") gets it wrong for exactly the
 * deployments that are not AWS, and the cost of being wrong is the `Host` header signing something
 * different from what was routed.
 */
class S3Router(
    private val virtualHostSuffixes: List<String> = emptyList(),
) {
    sealed interface Route {
        /** `GET /` */
        data object ListBuckets : Route

        data class CreateBucket(
            val bucket: String,
        ) : Route

        data class DeleteBucket(
            val bucket: String,
        ) : Route

        data class HeadBucket(
            val bucket: String,
        ) : Route

        data class GetBucketLocation(
            val bucket: String,
        ) : Route

        data class ListObjectsV2(
            val bucket: String,
        ) : Route

        data class ListObjects(
            val bucket: String,
        ) : Route

        data class ListMultipartUploads(
            val bucket: String,
        ) : Route

        data class DeleteObjects(
            val bucket: String,
        ) : Route

        data class PutObject(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        data class GetObject(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        data class HeadObject(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        data class DeleteObject(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        data class CreateMultipartUpload(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        data class UploadPart(
            val bucket: String,
            val key: ObjectKey,
            val uploadId: String,
            val partNumber: Int,
        ) : Route

        data class CompleteMultipartUpload(
            val bucket: String,
            val key: ObjectKey,
            val uploadId: String,
        ) : Route

        data class AbortMultipartUpload(
            val bucket: String,
            val key: ObjectKey,
            val uploadId: String,
        ) : Route

        data class ListParts(
            val bucket: String,
            val key: ObjectKey,
            val uploadId: String,
        ) : Route

        /**
         * A well-formed request for something bochka does not implement.
         *
         * Deliberately not the same as a malformed one: `GET /bucket?versions` deserves
         * `NotImplemented`, and answering it with an empty listing would be a lie shaped exactly
         * like an answer — the client would conclude there are no versions.
         */
        data class NotImplemented(
            val what: String,
        ) : Route
    }

    fun route(
        method: String,
        host: String,
        path: String,
        query: String,
    ): Route {
        val params = parseQuery(query)
        val bucketFromHost = bucketFromHost(host)

        val trimmed = path.removePrefix("/")
        val bucket: String
        val rawKey: String
        if (bucketFromHost != null) {
            bucket = bucketFromHost
            rawKey = trimmed
        } else {
            bucket = trimmed.substringBefore('/')
            rawKey = trimmed.substringAfter('/', "")
        }

        if (bucket.isEmpty()) {
            return if (method == "GET") Route.ListBuckets else Route.NotImplemented("$method /")
        }

        return if (rawKey.isEmpty()) {
            bucketRoute(method, bucket, params)
        } else {
            objectRoute(method, bucket, rawKey, params)
        }
    }

    private fun bucketRoute(
        method: String,
        bucket: String,
        params: Map<String, String>,
    ): Route =
        when (method) {
            "GET" -> {
                when {
                    "location" in params -> {
                        Route.GetBucketLocation(bucket)
                    }

                    "uploads" in params -> {
                        Route.ListMultipartUploads(bucket)
                    }

                    params["list-type"] == "2" -> {
                        Route.ListObjectsV2(bucket)
                    }

                    // Everything else with a query on a bucket is a sub-resource we do not have —
                    // versioning, acl, policy, lifecycle. Falling through to ListObjects would
                    // answer a question nobody asked.
                    params.keys.any { it in BUCKET_SUBRESOURCES } -> {
                        Route.NotImplemented("GET /$bucket?${params.keys.first { it in BUCKET_SUBRESOURCES }}")
                    }

                    else -> {
                        Route.ListObjects(bucket)
                    }
                }
            }

            "PUT" -> {
                if (params.keys.any { it in BUCKET_SUBRESOURCES }) {
                    Route.NotImplemented("PUT /$bucket?…")
                } else {
                    Route.CreateBucket(bucket)
                }
            }

            "DELETE" -> {
                if (params.keys.any { it in BUCKET_SUBRESOURCES }) {
                    Route.NotImplemented("DELETE /$bucket?…")
                } else {
                    Route.DeleteBucket(bucket)
                }
            }

            "HEAD" -> {
                Route.HeadBucket(bucket)
            }

            "POST" -> {
                if ("delete" in params) Route.DeleteObjects(bucket) else Route.NotImplemented("POST /$bucket")
            }

            else -> {
                Route.NotImplemented("$method /$bucket")
            }
        }

    private fun objectRoute(
        method: String,
        bucket: String,
        rawKey: String,
        params: Map<String, String>,
    ): Route {
        val key = ObjectKey(UriCodec.decode(rawKey))
        val uploadId = params["uploadId"]

        return when (method) {
            "PUT" -> {
                when {
                    uploadId != null && params["partNumber"] != null -> {
                        val number = params.getValue("partNumber").toIntOrNull()
                        if (number == null) {
                            Route.NotImplemented("partNumber=${params["partNumber"]}")
                        } else {
                            Route.UploadPart(bucket, key, uploadId, number)
                        }
                    }

                    params.keys.any { it in OBJECT_SUBRESOURCES } -> {
                        Route.NotImplemented("PUT object sub-resource")
                    }

                    else -> {
                        Route.PutObject(bucket, key)
                    }
                }
            }

            "GET" -> {
                when {
                    uploadId != null -> Route.ListParts(bucket, key, uploadId)
                    params.keys.any { it in OBJECT_SUBRESOURCES } -> Route.NotImplemented("GET object sub-resource")
                    else -> Route.GetObject(bucket, key)
                }
            }

            "HEAD" -> {
                Route.HeadObject(bucket, key)
            }

            "POST" -> {
                when {
                    "uploads" in params -> Route.CreateMultipartUpload(bucket, key)
                    uploadId != null -> Route.CompleteMultipartUpload(bucket, key, uploadId)
                    else -> Route.NotImplemented("POST /$bucket/…")
                }
            }

            "DELETE" -> {
                when {
                    uploadId != null -> Route.AbortMultipartUpload(bucket, key, uploadId)
                    params.keys.any { it in OBJECT_SUBRESOURCES } -> Route.NotImplemented("DELETE object sub-resource")
                    else -> Route.DeleteObject(bucket, key)
                }
            }

            else -> {
                Route.NotImplemented("$method /$bucket/…")
            }
        }
    }

    /** `null` means path-style: no configured suffix matched, so the first path segment is it. */
    private fun bucketFromHost(host: String): String? {
        val name = host.substringBefore(':').lowercase()
        for (suffix in virtualHostSuffixes) {
            val dotted = ".${suffix.lowercase()}"
            if (name.endsWith(dotted) && name.length > dotted.length) {
                return name.dropLast(dotted.length)
            }
        }
        return null
    }

    private fun parseQuery(query: String): Map<String, String> =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { token ->
                val eq = token.indexOf('=')
                val name = if (eq < 0) token else token.substring(0, eq)
                val value = if (eq < 0) "" else token.substring(eq + 1)
                String(UriCodec.decode(name, plusIsSpace = true)) to String(UriCodec.decode(value, plusIsSpace = true))
            }

    private companion object {
        /** Sub-resources of a bucket that exist in S3 and not here. Listed so they can be refused by name. */
        val BUCKET_SUBRESOURCES =
            setOf(
                "acl",
                "policy",
                "versioning",
                "versions",
                "lifecycle",
                "cors",
                "tagging",
                "notification",
                "replication",
                "encryption",
                "logging",
                "website",
                "object-lock",
                "requestPayment",
                "accelerate",
                "publicAccessBlock",
                "ownershipControls",
            )

        val OBJECT_SUBRESOURCES =
            setOf(
                "acl",
                "tagging",
                "retention",
                "legal-hold",
                "attributes",
                "torrent",
                "restore",
            )
    }
}
