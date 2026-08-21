package io.github.youndie.bochka.s3

/**
 * Who may do what to a bucket and to an object, given an owner and a canned ACL (M27).
 *
 * **This is the second model of permissions in the server, and the order between them is decided
 * rather than discovered** (M-196): the signature runs first, then the key scope
 * ([io.github.youndie.bochka.s3.sigv4.KeyScope]), then this. Every step may only take away. A
 * read-only key does not write into a `public-read-write` bucket, and a key that cannot see a
 * bucket does not see it because the bucket is public. The owner is not an exception either — full
 * control applies inside the scope, never over it — because a model where ownership outranks
 * configuration turns a typo in a canned name into a way around the deployment's own settings.
 *
 * **The unit of this model is the bucket, and a bucket with no recorded owner has no model at
 * all.** Buckets created before M27 carry no owner, and every key that could use them yesterday
 * can use them today: an upgrade that quietly answered `403` where it used to answer bytes would
 * be indistinguishable, from outside, from data loss. That rule covers objects too — an object in
 * an ownerless bucket is unrestricted, whenever it was written — because the alternative locks a
 * shared bucket by halves as new objects arrive.
 *
 * What this is **not**: grants. A grant names a user, and this server has access keys and no user
 * table; `AccessControlPolicy` with a list of grantees would be a permission language over people
 * who do not exist, which is the fiction ACLs were refused for in the first place. Only the canned
 * names are accepted, and everything else is refused **by name** rather than stored and ignored —
 * a permission accepted and not enforced is discovered as a leak.
 */
object AccessControl {
    /**
     * The canned ACLs this server understands, spelled as they travel.
     *
     * Six of them, and the two `bucket-owner-*` ones are here because the suite sends them eleven
     * times and they mean something exact in this model: the bucket's owner, who is a key like any
     * other. `log-delivery-write` is deliberately absent — it grants to a group, and groups are
     * the part of ACLs this server does not have.
     */
    enum class Canned(
        val wireName: String,
    ) {
        PRIVATE("private"),
        PUBLIC_READ("public-read"),
        PUBLIC_READ_WRITE("public-read-write"),
        AUTHENTICATED_READ("authenticated-read"),
        BUCKET_OWNER_READ("bucket-owner-read"),
        BUCKET_OWNER_FULL_CONTROL("bucket-owner-full-control"),
        ;

        companion object {
            fun of(name: String?): Canned? = entries.firstOrNull { it.wireName == name }
        }
    }

    /**
     * What an operation wants from a resource.
     *
     * `READ` and `WRITE` are about the data, `READ_ACP` and `WRITE_ACP` about the ACL itself, and
     * the last two are separate because a canned ACL that opens the data to everybody never opens
     * its own ACL to anybody: `public-read` is not "public-read-and-rewritable-permissions".
     */
    enum class Permission {
        READ,
        WRITE,
        READ_ACP,
        WRITE_ACP,
    }

    /**
     * A bucket or an object as this model sees it: who owns it and how it is shared.
     *
     * [owner] `null` means the owner was never recorded, which is a fact about the log rather than
     * about the resource — see the note on the unit of this model above. [acl] `null` means nobody
     * has named one, and that reads as [Canned.PRIVATE]: an object written without an ACL into a
     * bucket that has one is private, which is what the suite means by "b gets default (private)".
     */
    data class Resource(
        val owner: String?,
        val acl: String?,
    ) {
        val unrestricted: Boolean get() = owner == null

        val canned: Canned get() = Canned.of(acl) ?: Canned.PRIVATE
    }

    /**
     * Whether [requester] may do [permission] to [resource].
     *
     * [requester] `null` is an unsigned request, and the answer for it is always `false` here.
     * That is layer one of three on purpose (M28): opening `public-read` to anonymous callers
     * turns "no signature means 403" from one branch anybody can read into a computation whose
     * bugs are holes rather than incompatibilities, and it gets its own milestone with its own
     * negative tests.
     *
     * [bucketOwner] is needed only by the two `bucket-owner-*` names, and is the owner of the
     * bucket the object lives in — for a bucket resource it is the resource's own owner.
     */
    fun allows(
        resource: Resource,
        requester: String?,
        permission: Permission,
        bucketOwner: String? = null,
    ): Boolean {
        // Unrestricted means unrestricted **among keys**, and the qualifier is the whole of it
        // (M28). "A bucket with no recorded owner has no model" was written when an unsigned
        // request could not get past the signature at all; without this line, switching layer two
        // on would have opened every bucket made before M27 to the world — retroactively, silently,
        // and by combining two rules that are each correct alone.
        if (resource.unrestricted) return requester != null
        if (requester == null) {
            // Layer two, and the whole of it is these two lines (M28). Nobody claimed to be
            // anybody, so ownership cannot help and neither can `authenticated-read` — its name
            // means "every key", and a request with no key is not one of them however public the
            // deployment feels. What is left is exactly what the two `public-*` names say about
            // the data, and never anything about the ACL itself.
            return when (resource.canned) {
                Canned.PUBLIC_READ -> permission == Permission.READ
                Canned.PUBLIC_READ_WRITE -> permission == Permission.READ || permission == Permission.WRITE
                else -> false
            }
        }
        if (requester == resource.owner) return true
        return when (resource.canned) {
            Canned.PRIVATE -> false

            Canned.PUBLIC_READ -> permission == Permission.READ

            Canned.PUBLIC_READ_WRITE -> permission == Permission.READ || permission == Permission.WRITE

            // Everyone with a key, which is what "authenticated" means to a server whose users are
            // its keys. The unsigned case never reaches here.
            Canned.AUTHENTICATED_READ -> permission == Permission.READ

            Canned.BUCKET_OWNER_READ -> requester == bucketOwner && permission == Permission.READ

            Canned.BUCKET_OWNER_FULL_CONTROL -> requester == bucketOwner
        }
    }

    /**
     * The rule the access matrix of the suite turns on, and the one place intuition goes wrong.
     *
     * **Writing an object is governed by the bucket alone; reading it is governed by the object
     * alone.** A `public-read-write` bucket accepts an overwrite of a *private* object from
     * anybody (`test_access_bucket_publicreadwrite_object_private`), and a `public-read` bucket
     * hands out no bytes of a private object it contains
     * (`test_access_bucket_publicread_object_private`). The tempting model — "the object ACL
     * narrows the bucket ACL" — is wrong in both directions at once.
     */
    fun allowsObjectRead(
        obj: Resource,
        requester: String?,
        bucketOwner: String?,
    ): Boolean = allows(obj, requester, Permission.READ, bucketOwner)

    fun allowsObjectWrite(
        bucket: Resource,
        requester: String?,
    ): Boolean = allows(bucket, requester, Permission.WRITE, bucket.owner)
}
