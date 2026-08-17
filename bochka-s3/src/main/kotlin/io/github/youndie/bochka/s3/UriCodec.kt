package io.github.youndie.bochka.s3

/**
 * Percent-encoding, in the three shapes S3 actually uses.
 *
 * The interesting part is that they are **not** one function. A client library gets away with a
 * single encoder — the string it signs and the string it sends have to be identical, so two
 * encoders that differ by one character produce a signature mismatch that names nothing. A server
 * has the opposite problem: what arrives on the request line and what goes back in a listing are
 * encoded by **different rules**, and using one function for both is wrong in a way no test of a
 * round trip would catch.
 *
 * | Byte | On the request path | In a listing response |
 * |---|---|---|
 * | space | `%20` | `+` |
 * | `~` | left alone | `%7E` |
 * | `*` | `%2A` | left alone |
 * | `/` | left alone | left alone |
 *
 * Sources, both in the repository: the path column is `docs/spec/s3-signing-vectors/key-encoding`,
 * produced by Python's `quote` through botocore — `a~b` comes back as `a~b`, `my dir/file.txt` as
 * `my%20dir/file.txt`. The response column is the reference server's `s3URLEncode`
 * (`minio/minio`, `cmd/api-utils.go:28-44`), whose own comment lists the exceptions: "Avoid
 * encoding '/' and '*'", "Force encoding of '~'".
 */
object UriCodec {
    private const val HEX = "0123456789ABCDEF"

    /**
     * Request path (or a query value) to the bytes it stands for.
     *
     * Three things this deliberately does not do:
     *
     * * **no normalisation.** `a/./b` decodes to `a/./b`. S3 signs the path exactly as it travels
     *   (`docs/spec/reference/botocore-auth.py:538`), and a key is whatever bytes were sent — a
     *   server that collapses dot segments both breaks the signature and stores something else;
     * * **`+` is a plus.** Form decoding turns it into a space; a path does not. The response side
     *   is the one that uses `+`, which is exactly the asymmetry this class exists to keep
     *   straight;
     * * **no charset guessing.** [raw] is the request line as bytes widened to chars — read the
     *   line as ISO-8859-1 and every byte survives. A char above 0xFF means somebody decoded it as
     *   UTF-8 first and the original bytes are already gone.
     */
    fun decode(
        raw: String,
        plusIsSpace: Boolean = false,
    ): ByteArray {
        val out = ByteArray(raw.length)
        var n = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                plusIsSpace && c == '+' -> {
                    out[n++] = ' '.code.toByte()
                    i++
                }

                c == '%' -> {
                    require(i + 2 < raw.length) { "truncated percent escape at $i in '$raw'" }
                    val hi = hexDigit(raw[i + 1], raw, i)
                    val lo = hexDigit(raw[i + 2], raw, i)
                    out[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                }

                c.code <= 0xFF -> {
                    out[n++] = c.code.toByte()
                    i++
                }

                else -> {
                    throw IllegalArgumentException(
                        "char U+%04X at %d is not a byte; read the request line as ISO-8859-1".format(c.code, i),
                    )
                }
            }
        }
        return out.copyOf(n)
    }

    /**
     * Bytes to a path, the way the signature expects to see it.
     *
     * Unreserved is `A-Za-z0-9-_.~`; `/` stays because it separates segments. Checked against
     * `docs/spec/s3-signing-vectors/key-encoding`.
     */
    fun encodePath(bytes: ByteArray): String = encode(bytes, spaceAsPlus = false, ::unreservedOnPath)

    /**
     * Bytes to the form a listing uses when the caller asked for `encoding-type=url`.
     *
     * Space becomes `+`, not `%20`, and `~` gets escaped — neither is what the path does. This
     * matches the reference implementation and, through it, AWS: the official client decodes these
     * fields with `unquote_plus` (`botocore/compat.py`), so `+` is what it expects to see. There is
     * no ambiguity in the other direction either, because a literal `+` in a key comes back as
     * `%2B`.
     */
    fun encodeForListing(bytes: ByteArray): String = encode(bytes, spaceAsPlus = true, ::unreservedInListing)

    /**
     * Bytes to a query component, for canonicalising a request before checking its signature.
     *
     * Unreserved is `A-Za-z0-9-_.~` and **nothing else** — `/` is escaped here, because in a query
     * it is a value rather than a separator (`docs/spec/reference/botocore-auth.py:268`). This is
     * the third rule in this class and the narrowest of them.
     */
    fun encodeQueryComponent(bytes: ByteArray): String = encode(bytes, spaceAsPlus = false, ::unreservedInQuery)

    private inline fun encode(
        bytes: ByteArray,
        spaceAsPlus: Boolean,
        unreserved: (Int) -> Boolean,
    ): String {
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                unreserved(v) -> {
                    out.append(v.toChar())
                }

                spaceAsPlus && v == ' '.code -> {
                    out.append('+')
                }

                else -> {
                    out.append('%')
                    out.append(HEX[v shr 4])
                    out.append(HEX[v and 0x0F])
                }
            }
        }
        return out.toString()
    }

    private fun alphanumeric(v: Int): Boolean =
        (v >= 'A'.code && v <= 'Z'.code) || (v >= 'a'.code && v <= 'z'.code) || (v >= '0'.code && v <= '9'.code)

    private fun unreservedOnPath(v: Int): Boolean =
        alphanumeric(v) || v == '-'.code || v == '_'.code || v == '.'.code || v == '~'.code || v == '/'.code

    private fun unreservedInListing(v: Int): Boolean =
        alphanumeric(v) || v == '-'.code || v == '_'.code || v == '.'.code || v == '/'.code || v == '*'.code

    private fun unreservedInQuery(v: Int): Boolean =
        alphanumeric(v) || v == '-'.code || v == '_'.code || v == '.'.code || v == '~'.code

    private fun hexDigit(
        c: Char,
        raw: String,
        at: Int,
    ): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("bad percent escape at $at in '$raw'")
        }
}
