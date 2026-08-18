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

        /**
         * `GET /<bucket>?versions`, and it is here despite versioning being out of scope.
         *
         * A client uses it to list a bucket that has no versioning — the answer is the objects at
         * version `null`. Refusing it is what makes a store unusable rather than unversioned:
         * the compatibility suite calls it before every test to clean up.
         */
        data class ListObjectVersions(
            val bucket: String,
        ) : Route

        /**
         * Upload by an HTML form: `POST /<bucket>` with a `multipart/form-data` body.
         *
         * The key, the policy and the signature all live inside the body, so the router has
         * nothing to route on beyond the method — everything else is decided after the form is
         * parsed. That inversion is the whole point of the operation, not an omission here.
         */
        data class PostObject(
            val bucket: String,
        ) : Route

        data class DeleteObjects(
            val bucket: String,
        ) : Route

        data class PutObject(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        /**
         * `PUT /<bucket>/<key>` with `x-amz-copy-source` — the same request line as a `PutObject`
         * and a different operation.
         *
         * The one route decided by a header rather than by the method, the path or the query, and
         * it is why [route] takes the copy source at all. Missing it does not produce a wrong
         * answer, it produces a `PutObject` with an empty body: the object is created, it is zero
         * bytes long, and the client is told it succeeded.
         */
        data class CopyObject(
            val bucket: String,
            val key: ObjectKey,
            val sourceBucket: String,
            val sourceKey: ObjectKey,
        ) : Route

        data class GetObject(
            val bucket: String,
            val key: ObjectKey,
            /**
             * `?partNumber=N` — read the bytes that arrived as that part of a multipart upload.
             *
             * A `Range` the client does not have to know the arithmetic for: the server remembers
             * where the seams were, so a resumable download can ask for the piece rather than
             * compute an offset it would have to be told anyway.
             */
            val partNumber: Int? = null,
        ) : Route

        /**
         * `GET /<bucket>/<key>?attributes` — the object's shape without its bytes.
         *
         * A separate operation rather than a flavour of `HEAD`, because it answers things a `HEAD`
         * has no header for: how many parts the object has and how long each one was. Which of
         * them to answer is `x-amz-object-attributes`, a header rather than a query parameter.
         */
        data class GetObjectAttributes(
            val bucket: String,
            val key: ObjectKey,
        ) : Route

        /**
         * Именованная настройка бакета: `?tagging`, `?cors`.
         *
         * Одним маршрутом на три метода и на оба имени, потому что различаются они только тем,
         * какой документ разбирать, — а это вопрос к слою, который знает документы.
         */
        data class BucketSubresource(
            val bucket: String,
            val name: String,
            val method: String,
        ) : Route

        /** `?tagging` у объекта: те же три метода, но теги живут в метаданных объекта. */
        data class ObjectTagging(
            val bucket: String,
            val key: ObjectKey,
            val method: String,
        ) : Route

        /**
         * `OPTIONS` — preflight, и он единственный не подписывается.
         *
         * Браузер шлёт его до всякой авторизации: у клиентского кода в этот момент нет ни ключа,
         * ни повода его показывать. Проверка подписи для него отключается **по маршруту**, а не
         * по методу вообще, чтобы «неподписанный» не расползлось.
         */
        data class Preflight(
            val bucket: String,
        ) : Route

        data class HeadObject(
            val bucket: String,
            val key: ObjectKey,
            /**
             * `?partNumber=N` on a `HEAD`, which is how a client finds out how many parts an
             * object has before downloading any of them.
             *
             * It was missing here while `GetObject` had it, and the shape of that bug is worth
             * keeping in mind: the request succeeded, answered the whole object's headers, and
             * carried no `x-amz-mp-parts-count` — so a client asking "how many parts" was told
             * nothing rather than told wrongly, and read `KeyError: 'PartsCount'`.
             */
            val partNumber: Int? = null,
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

        /**
         * `UploadPart` whose bytes come from another object rather than from the request body.
         *
         * How a client rewrites a large object without moving it through itself: copy the parts it
         * keeps, upload the ones it changes. `x-amz-copy-source-range` narrows the source to a
         * range of it, which is also how a client makes parts of a size the server will accept out
         * of an object that has none.
         */
        data class UploadPartCopy(
            val bucket: String,
            val key: ObjectKey,
            val uploadId: String,
            val partNumber: Int,
            val sourceBucket: String,
            val sourceKey: ObjectKey,
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
         * Deliberately not the same as a malformed one: `GET /bucket?lifecycle` deserves
         * `NotImplemented`, and answering it with an empty configuration would be a lie shaped
         * exactly like an answer — the client would conclude there are no rules.
         *
         * The line between the two is narrower than it looks. `?versions` was on this side of it
         * until the compatibility suite showed otherwise: listing versions of a bucket that has no
         * versioning is not an unimplemented feature, it is a question with a defined answer
         * (every object at version `null`). Refusing it made 837 of 838 tests error in a cleanup
         * fixture before reaching anything they were about.
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
        copySource: String? = null,
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
            objectRoute(method, bucket, rawKey, params, copySource)
        }
    }

    /**
     * `x-amz-copy-source` is `/<bucket>/<key>` or `<bucket>/<key>`, percent-encoded, and may carry
     * `?versionId=`.
     *
     * Decoded here and not by the caller because the split is positional: the first segment is the
     * bucket and **everything after it** is the key, slashes and all. Splitting a decoded string
     * would put a key containing `%2F` in the wrong place.
     */
    private fun parseCopySource(value: String): Pair<String, ObjectKey>? {
        val withoutVersion = value.substringBefore('?')
        val trimmed = withoutVersion.removePrefix("/")
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return null
        val bucket = String(UriCodec.decode(trimmed.substring(0, slash)))
        val key = ObjectKey(UriCodec.decode(trimmed.substring(slash + 1)))
        return bucket to key
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

                    "versions" in params -> {
                        Route.ListObjectVersions(bucket)
                    }

                    params["list-type"] == "2" -> {
                        Route.ListObjectsV2(bucket)
                    }

                    // Everything else with a query on a bucket is a sub-resource we do not have —
                    // versioning, acl, policy, lifecycle. Falling through to ListObjects would
                    // answer a question nobody asked.
                    params.keys.any { it in CONFIGURABLE_SUBRESOURCES } -> {
                        Route.BucketSubresource(bucket, params.keys.first { it in CONFIGURABLE_SUBRESOURCES }, "GET")
                    }

                    params.keys.any { it in BUCKET_SUBRESOURCES } -> {
                        Route.NotImplemented("GET /$bucket?${params.keys.first { it in BUCKET_SUBRESOURCES }}")
                    }

                    else -> {
                        Route.ListObjects(bucket)
                    }
                }
            }

            "PUT" -> {
                when {
                    params.keys.any { it in CONFIGURABLE_SUBRESOURCES } -> {
                        Route.BucketSubresource(bucket, params.keys.first { it in CONFIGURABLE_SUBRESOURCES }, "PUT")
                    }

                    params.keys.any { it in BUCKET_SUBRESOURCES } -> {
                        Route.NotImplemented("PUT /$bucket?…")
                    }

                    else -> {
                        Route.CreateBucket(bucket)
                    }
                }
            }

            "DELETE" -> {
                when {
                    params.keys.any { it in CONFIGURABLE_SUBRESOURCES } -> {
                        Route.BucketSubresource(bucket, params.keys.first { it in CONFIGURABLE_SUBRESOURCES }, "DELETE")
                    }

                    params.keys.any { it in BUCKET_SUBRESOURCES } -> {
                        Route.NotImplemented("DELETE /$bucket?…")
                    }

                    else -> {
                        Route.DeleteBucket(bucket)
                    }
                }
            }

            "OPTIONS" -> {
                Route.Preflight(bucket)
            }

            "HEAD" -> {
                Route.HeadBucket(bucket)
            }

            "POST" -> {
                if ("delete" in params) Route.DeleteObjects(bucket) else Route.PostObject(bucket)
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
        copySource: String?,
    ): Route {
        val key = ObjectKey(UriCodec.decode(rawKey))
        val uploadId = params["uploadId"]

        // Preflight приходит на тот адрес, который браузер собирается запросить, — то есть чаще
        // на объект, чем на бакет. Правила при этом принадлежат бакету, поэтому ключ здесь
        // не нужен и маршрут тот же.
        if (method == "OPTIONS") return Route.Preflight(bucket)

        return when (method) {
            "PUT" -> {
                when {
                    uploadId != null && params["partNumber"] != null -> {
                        val number = params.getValue("partNumber").toIntOrNull()
                        val source = copySource?.let(::parseCopySource)
                        when {
                            number == null -> {
                                Route.NotImplemented("partNumber=${params["partNumber"]}")
                            }

                            copySource == null -> {
                                Route.UploadPart(bucket, key, uploadId, number)
                            }

                            source == null -> {
                                Route.NotImplemented("x-amz-copy-source: $copySource")
                            }

                            else -> {
                                Route.UploadPartCopy(bucket, key, uploadId, number, source.first, source.second)
                            }
                        }
                    }

                    "tagging" in params -> {
                        Route.ObjectTagging(bucket, key, "PUT")
                    }

                    params.keys.any { it in OBJECT_SUBRESOURCES } -> {
                        Route.NotImplemented("PUT object sub-resource")
                    }

                    copySource != null -> {
                        val source = parseCopySource(copySource)
                        if (source == null) {
                            Route.NotImplemented("x-amz-copy-source: $copySource")
                        } else {
                            Route.CopyObject(bucket, key, source.first, source.second)
                        }
                    }

                    else -> {
                        if ("tagging" in
                            params
                        ) {
                            Route.ObjectTagging(bucket, key, "PUT")
                        } else {
                            Route.PutObject(bucket, key)
                        }
                    }
                }
            }

            "GET" -> {
                when {
                    uploadId != null -> Route.ListParts(bucket, key, uploadId)
                    "attributes" in params -> Route.GetObjectAttributes(bucket, key)
                    "tagging" in params -> Route.ObjectTagging(bucket, key, "GET")
                    params.keys.any { it in OBJECT_SUBRESOURCES } -> Route.NotImplemented("GET object sub-resource")
                    else -> Route.GetObject(bucket, key, params["partNumber"]?.toIntOrNull())
                }
            }

            "HEAD" -> {
                Route.HeadObject(bucket, key, params["partNumber"]?.toIntOrNull())
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
                    "tagging" in params -> Route.ObjectTagging(bucket, key, "DELETE")
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
        /**
         * Настройки, которые bochka **хранит**, а не отвергает.
         *
         * Список нарочно отдельный от [BUCKET_SUBRESOURCES] и проверяется раньше: то, что мы
         * умеем, должно перехватываться до общего отказа, и добавление новой настройки — это
         * строчка здесь, а не правка трёх ветвей маршрутизации.
         */
        val CONFIGURABLE_SUBRESOURCES = setOf("tagging", "cors", "versioning")

        /** Sub-resources of a bucket that exist in S3 and not here. Listed so they can be refused by name. */
        val BUCKET_SUBRESOURCES =
            setOf(
                "acl",
                "policy",
                "versioning",
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
                "torrent",
                "restore",
            )
    }
}
