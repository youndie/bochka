package io.github.youndie.bochka.s3

/**
 * Whether a bucket is public — the whole of what `GetBucketPolicyStatus` answers (M-228).
 *
 * The operation is one boolean (`docs/spec/s3-service-2.json:639`, the `PolicyStatus` shape at
 * `:10038`), and the model says nothing at all about how to compute it: its documentation defers
 * to a page called "The Meaning of Public". So the definition lives here, written out, and every
 * rule below names the suite case it came from — the alternative is a definition assembled from
 * common sense, which in S3 is wrong more often than right.
 *
 * **Two sources, and the name of the operation only mentions one.** Two of the six cases that ask
 * set no policy whatever: they put a canned ACL and require `true`. None of the six carries a
 * marker, so none of them is describing RGW rather than S3 — checked before writing this, the way
 * M28 learned to.
 *
 * **This is a report, not a decision.** Nothing here grants or refuses anything; it describes what
 * [AccessControl] and [BucketPolicy] would do. That is why it is allowed to be **coarser** than
 * the evaluator in one place — see [statementIsPublic] on conditions — and why it must never be
 * looser: an answer of `false` about a bucket that is in fact open is the one error here that
 * hurts, because it is read by somebody checking whether they have a leak.
 */
object PolicyStatus {
    /**
     * `IsPublic` for a bucket with this [acl] and this [policy] (`null` when it has none).
     *
     * Either source alone is enough. A bucket whose ACL is private and whose policy hands
     * `s3:ListBucket` to `*` is public because of the policy; a bucket with no policy at all and
     * `public-read` on it is public because of the ACL.
     */
    fun isPublic(
        acl: String?,
        policy: BucketPolicy.Policy?,
    ): Boolean = aclIsPublic(acl) || (policy != null && BucketPolicy.isPublic(policy))

    /**
     * Whether a canned name opens the bucket to a **group** rather than to named keys.
     *
     * `public-read` (`test_get_public_acl_bucket_policy_status:14092`) and `authenticated-read`
     * (`test_get_authpublic_acl_bucket_policy_status:14099`) are the two the suite pins, and the
     * second is the interesting one: it opens nothing to an unsigned caller (§3.7), every key in
     * the deployment can already reach it, and the suite still calls that public. The reading that
     * fits both is "the grantee is a group, not a key" — which puts `public-read-write` here too,
     * unpinned, on the grounds that it is strictly wider than `public-read`.
     *
     * The other three names grant to a key: the owner, or the bucket's owner. A bucket nobody
     * named an ACL for reads as `private` and is not public
     * (`test_get_bucket_policy_status:14086`).
     */
    fun aclIsPublic(acl: String?): Boolean =
        when (AccessControl.Canned.of(acl)) {
            AccessControl.Canned.PUBLIC_READ,
            AccessControl.Canned.PUBLIC_READ_WRITE,
            AccessControl.Canned.AUTHENTICATED_READ,
            -> true

            else -> false
        }
}
