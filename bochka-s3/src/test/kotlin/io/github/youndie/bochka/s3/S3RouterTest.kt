package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Method and URI per operation: `docs/spec/s3-service-2.json`, `operations.*.http`.
 */
class S3RouterTest {
    private val pathStyle = S3Router()
    private val virtual = S3Router(virtualHostSuffixes = listOf("s3.example.com"))

    @Test
    fun `bucket operations route by method`() {
        assertEquals(S3Router.Route.ListBuckets, pathStyle.route("GET", "h", "/", ""))
        assertEquals(S3Router.Route.CreateBucket("photos"), pathStyle.route("PUT", "h", "/photos", ""))
        assertEquals(S3Router.Route.DeleteBucket("photos"), pathStyle.route("DELETE", "h", "/photos", ""))
        assertEquals(S3Router.Route.HeadBucket("photos"), pathStyle.route("HEAD", "h", "/photos", ""))
    }

    @Test
    fun `an operation can be decided by a query parameter with no value`() {
        // `?uploads`, `?delete` and `?location` carry nothing; they *are* the operation. A router
        // that only looks at name=value pairs routes all three to a listing.
        assertEquals(S3Router.Route.GetBucketLocation("photos"), pathStyle.route("GET", "h", "/photos", "location"))
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), pathStyle.route("GET", "h", "/photos", "uploads"))
        assertEquals(S3Router.Route.DeleteObjects("photos"), pathStyle.route("POST", "h", "/photos", "delete"))
        assertEquals(
            S3Router.Route.CreateMultipartUpload("photos", ObjectKey.of("big.bin")),
            pathStyle.route("POST", "h", "/photos/big.bin", "uploads"),
        )
    }

    @Test
    fun `the same method and path are different operations by query`() {
        val create = pathStyle.route("POST", "h", "/photos/big.bin", "uploads")
        val complete = pathStyle.route("POST", "h", "/photos/big.bin", "uploadId=abc")

        assertIs<S3Router.Route.CreateMultipartUpload>(create)
        assertEquals(S3Router.Route.CompleteMultipartUpload("photos", ObjectKey.of("big.bin"), "abc"), complete)
    }

    @Test
    fun `listing v1 and v2 are told apart by list-type`() {
        assertEquals(S3Router.Route.ListObjectsV2("photos"), pathStyle.route("GET", "h", "/photos", "list-type=2"))
        assertEquals(S3Router.Route.ListObjects("photos"), pathStyle.route("GET", "h", "/photos", ""))
        assertEquals(
            S3Router.Route.ListObjects("photos"),
            pathStyle.route("GET", "h", "/photos", "prefix=a&max-keys=10"),
        )
    }

    @Test
    fun `object operations carry the decoded key`() {
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("my dir/file.txt")),
            pathStyle.route("GET", "h", "/photos/my%20dir/file.txt", ""),
        )
        assertEquals(
            S3Router.Route.PutObject("photos", ObjectKey.of("a/b/c")),
            pathStyle.route("PUT", "h", "/photos/a/b/c", ""),
        )
        // %2F is a byte of the key, not a separator: the key is `a/b`, one segment on the wire.
        assertEquals(
            S3Router.Route.DeleteObject("photos", ObjectKey.of("a/b")),
            pathStyle.route("DELETE", "h", "/photos/a%2Fb", ""),
        )
    }

    @Test
    fun `an upload part carries its number and upload id`() {
        assertEquals(
            S3Router.Route.UploadPart("photos", ObjectKey.of("big.bin"), "abc", 7),
            pathStyle.route("PUT", "h", "/photos/big.bin", "partNumber=7&uploadId=abc"),
        )
        assertEquals(
            S3Router.Route.ListParts("photos", ObjectKey.of("big.bin"), "abc"),
            pathStyle.route("GET", "h", "/photos/big.bin", "uploadId=abc"),
        )
        assertEquals(
            S3Router.Route.AbortMultipartUpload("photos", ObjectKey.of("big.bin"), "abc"),
            pathStyle.route("DELETE", "h", "/photos/big.bin", "uploadId=abc"),
        )
    }

    @Test
    fun `virtual-hosted addressing takes the bucket from the host`() {
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
            virtual.route("GET", "photos.s3.example.com", "/a.txt", ""),
        )
        assertEquals(
            S3Router.Route.ListObjectsV2("photos"),
            virtual.route("GET", "photos.s3.example.com", "/", "list-type=2"),
        )
        assertEquals(S3Router.Route.ListBuckets, virtual.route("GET", "s3.example.com", "/", ""))
    }

    @Test
    fun `a host that is not a configured suffix is path-style`() {
        // No guessing: an unconfigured domain means the bucket is in the path, even when the host
        // looks like it could carry one. Guessing wrong makes Host sign one thing and route another.
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
            virtual.route("GET", "photos.s3.elsewhere.com", "/photos/a.txt", ""),
        )
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
            pathStyle.route("GET", "127.0.0.1:9000", "/photos/a.txt", ""),
        )
    }

    @Test
    fun `the port is not part of the host when matching a suffix`() {
        assertEquals(
            S3Router.Route.HeadObject("photos", ObjectKey.of("a.txt")),
            virtual.route("HEAD", "photos.s3.example.com:9000", "/a.txt", ""),
        )
    }

    @Test
    fun `listing versions is answered, because a bucket without versioning still has an answer`() {
        // It looks like a versioning feature and is not one: `?versions` is how a client lists a
        // bucket that has no versioning, and the answer is the objects at version `null`. Refusing
        // it makes a store unusable rather than unversioned — the compatibility suite calls it
        // before every single test to clean up, and a 501 errored 837 of 838 tests before any of
        // them reached what they check.
        assertEquals(S3Router.Route.ListObjectVersions("photos"), pathStyle.route("GET", "h", "/photos", "versions"))
    }

    @Test
    fun `what bochka does not implement is refused by name rather than answered`() {
        // The important half. `GET /photos?versions` answered with an empty listing tells the
        // client there are no versions, which is a lie shaped exactly like an answer.
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos", "notification"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos/a.txt", "acl"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PATCH", "h", "/photos/a.txt", ""))
    }

    @Test
    fun `а теги и CORS перехватываются до общего отказа`() {
        // Эта строчка раньше стояла в тесте выше: `?tagging` отвергался по имени. Список отказов —
        // это запись рамок, и когда рамки меняются, меняется он, а не поведение под него.
        assertIs<S3Router.Route.ObjectTagging>(pathStyle.route("PUT", "h", "/photos/a.txt", "tagging"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "tagging"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "cors"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("DELETE", "h", "/photos", "cors"))
        // `?versioning` moved across this line in M-103: it was refused by name, and now it is
        // answered — a bucket nobody configured has a defined empty configuration, and that is not
        // the same as a feature the server does not have.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "versioning"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "versioning"))
        // И третий переезд, M20: `?policy`, `?lifecycle` и `?acl` отвечают на `GET` — «настройки
        // нет» это вопрос с определённым ответом, — но **только** на `GET`. Принимающая сторона
        // остаётся отказом, и обе половины проверяются здесь рядом, чтобы одну нельзя было
        // подвинуть, не заметив другую.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "policy"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "acl"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PUT", "h", "/photos", "policy"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PUT", "h", "/photos", "acl"))
        // И четвёртый переезд, M21: `?lifecycle` был среди отвечающих на `GET` и отвергающих
        // всё остальное — а стал настройкой на трёх методах. Направление у переездов одно:
        // подресурс уходит из отвергающих тогда, когда сервер начинает **делать** то, что тот
        // описывает, а не когда отвечать стало удобно.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "lifecycle"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "lifecycle"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("DELETE", "h", "/photos", "lifecycle"))
    }

    @Test
    fun `preflight маршрутизируется одинаково от бакета и от объекта`() {
        // Правила принадлежат бакету, а браузер шлёт `OPTIONS` на тот адрес, который собирается
        // запросить, — то есть чаще на объект. Ключ здесь не нужен ни для чего.
        assertIs<S3Router.Route.Preflight>(pathStyle.route("OPTIONS", "h", "/photos", ""))
        assertIs<S3Router.Route.Preflight>(pathStyle.route("OPTIONS", "h", "/photos/a.txt", ""))
    }
}
