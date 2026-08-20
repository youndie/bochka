package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canned ACL matrix (M27).
 *
 * Written against `ceph/s3-tests`' own matrix — `test_access_bucket_*`, the twelve cases built by
 * `_setup_access` — because that is the only description of these rules that is not somebody's
 * paraphrase. Most of the assertions here are refusals: the suite checks far more often that the
 * allowed is allowed than that the forbidden is forbidden, and permissions are the one thing where
 * a green test proves nothing on its own.
 */
class AccessControlTest {
    private val main = "s3main"
    private val alt = "s3alt"

    private fun bucket(acl: String?) = AccessControl.Resource(owner = main, acl = acl)

    private fun obj(acl: String?) = AccessControl.Resource(owner = main, acl = acl)

    @Test
    fun `the owner may do everything to what it owns`() {
        for (permission in AccessControl.Permission.entries) {
            assertTrue(AccessControl.allows(bucket("private"), main, permission))
            assertTrue(AccessControl.allows(obj("private"), main, permission))
        }
    }

    @Test
    fun `a private resource is closed to everybody else`() {
        for (permission in AccessControl.Permission.entries) {
            assertFalse(
                AccessControl.allows(bucket("private"), alt, permission),
                "a private bucket answers $permission to a stranger",
            )
        }
        // And an object with no ACL at all is private, which is what the suite means by
        // "b gets default (private)" in every case of its matrix.
        assertFalse(AccessControl.allowsObjectRead(obj(null), alt, bucketOwner = main))
    }

    @Test
    fun `reading an object is decided by the object, and writing one by the bucket`() {
        // `test_access_bucket_publicread_object_private`: the bucket is open to read, and the
        // private object inside it is still not readable.
        assertFalse(AccessControl.allowsObjectRead(obj("private"), alt, bucketOwner = main))
        assertTrue(AccessControl.allows(bucket("public-read"), alt, AccessControl.Permission.READ))

        // `test_access_bucket_publicreadwrite_object_private`: the same private object **is**
        // overwritable, because writing an object asks the bucket and never the object. This is
        // the assertion that kills the plausible model where the object ACL narrows the bucket's.
        assertTrue(AccessControl.allowsObjectWrite(bucket("public-read-write"), alt))
        assertFalse(AccessControl.allowsObjectWrite(bucket("public-read"), alt))

        // And the reverse: a readable object in a read-only bucket does not become writable
        // (`test_access_bucket_publicread_object_publicreadwrite`).
        assertTrue(AccessControl.allowsObjectRead(obj("public-read-write"), alt, bucketOwner = main))
        assertFalse(AccessControl.allowsObjectWrite(bucket("public-read"), alt))
    }

    @Test
    fun `authenticated-read opens to any key and to no unsigned request`() {
        assertTrue(AccessControl.allows(bucket("authenticated-read"), alt, AccessControl.Permission.READ))
        assertFalse(AccessControl.allows(bucket("authenticated-read"), alt, AccessControl.Permission.WRITE))
        assertFalse(
            AccessControl.allows(bucket("authenticated-read"), null, AccessControl.Permission.READ),
            "an unsigned request is not authenticated, whatever the name suggests",
        )
    }

    @Test
    fun `a public acl never opens the acl itself`() {
        // `public-read` grants READ of the data. Reading or rewriting the permissions stays with
        // the owner, and a model that let `public-read` hand out WRITE_ACP would let any caller
        // promote themselves to full control in one request.
        for (acl in listOf("public-read", "public-read-write", "authenticated-read")) {
            assertFalse(AccessControl.allows(bucket(acl), alt, AccessControl.Permission.READ_ACP), acl)
            assertFalse(AccessControl.allows(bucket(acl), alt, AccessControl.Permission.WRITE_ACP), acl)
        }
    }

    @Test
    fun `no unsigned request gets anything in this layer`() {
        // Layer two (M28) is where this changes, deliberately and with its own negative tests.
        for (acl in listOf("public-read", "public-read-write")) {
            assertFalse(AccessControl.allows(bucket(acl), null, AccessControl.Permission.READ), acl)
            assertFalse(AccessControl.allows(bucket(acl), null, AccessControl.Permission.WRITE), acl)
        }
    }

    @Test
    fun `the bucket owner gets what bucket-owner acls name, and nobody else does`() {
        val written = AccessControl.Resource(owner = alt, acl = "bucket-owner-read")
        assertTrue(AccessControl.allowsObjectRead(written, main, bucketOwner = main))
        assertFalse(AccessControl.allows(written, main, AccessControl.Permission.WRITE_ACP, bucketOwner = main))
        assertFalse(AccessControl.allowsObjectRead(written, "s3tenant", bucketOwner = main))

        val handed = AccessControl.Resource(owner = alt, acl = "bucket-owner-full-control")
        assertTrue(AccessControl.allows(handed, main, AccessControl.Permission.WRITE_ACP, bucketOwner = main))
        assertFalse(AccessControl.allows(handed, "s3tenant", AccessControl.Permission.READ, bucketOwner = main))
    }

    @Test
    fun `a resource whose owner was never recorded is open, and that is about the log`() {
        // The upgrade rule (M-196). A bucket created before this milestone has no owner, and every
        // key that could use it yesterday can use it today. Answering `403` there would look, from
        // outside, exactly like data that had gone missing.
        val legacy = AccessControl.Resource(owner = null, acl = null)
        for (permission in AccessControl.Permission.entries) {
            assertTrue(AccessControl.allows(legacy, alt, permission), permission.name)
        }
        assertTrue(AccessControl.allows(legacy, null, AccessControl.Permission.READ), "including unsigned")
    }

    @Test
    fun `a name this server does not enforce is not a canned acl`() {
        // `log-delivery-write` grants to a group, and groups are the half of ACLs this server does
        // not have. It has to come back as "no such canned name" so the caller is refused rather
        // than told yes and quietly given `private`.
        assertNull(AccessControl.Canned.of("log-delivery-write"))
        assertNull(AccessControl.Canned.of("public"))
        assertNull(AccessControl.Canned.of(null))
    }
}
