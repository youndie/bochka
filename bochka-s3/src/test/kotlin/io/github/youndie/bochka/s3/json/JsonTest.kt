package io.github.youndie.bochka.s3.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JSON reader a bucket policy is read with (M-201а).
 *
 * Half of these are about refusing rather than reading, and deliberately: this text arrives from
 * whoever can reach `PutBucketPolicy`, so the interesting cases are the malformed ones. The reader
 * exists because the project has no JSON dependency and is not acquiring one for a document this
 * small — see the KDoc on [Json].
 */
class JsonTest {
    @Test
    fun `the shape a policy is made of`() {
        val value =
            Json.parse(
                """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"AWS": "*"}}]}""",
            )

        val members = (value as JsonValue.Obj).members
        assertEquals(JsonValue.Str("2012-10-17"), members["Version"])
        val statements = (members["Statement"] as JsonValue.Arr).items
        assertEquals(1, statements.size)
        val first = (statements[0] as JsonValue.Obj).members
        assertEquals(JsonValue.Str("Allow"), first["Effect"])
        assertEquals(JsonValue.Str("*"), ((first["Principal"] as JsonValue.Obj).members)["AWS"])
    }

    @Test
    fun `numbers stay text`() {
        val members = (Json.parse("""{"n": 1e400}""") as JsonValue.Obj).members

        // Read as a Double this would be Infinity, and echoing it back would hand the client
        // something other than what it wrote.
        assertEquals(JsonValue.Num("1e400"), members["n"])
    }

    @Test
    fun `the empty object and the empty array are values`() {
        assertEquals(JsonValue.Obj(emptyMap()), Json.parse("{}"))
        assertEquals(JsonValue.Arr(emptyList()), Json.parse("[]"))
    }

    @Test
    fun `escapes come back as the characters they name`() {
        val members = (Json.parse("""{"k": "a\"b\\c\/d\neA"}""") as JsonValue.Obj).members

        assertEquals(JsonValue.Str("a\"b\\c/d\neA"), members["k"])
    }

    @Test
    fun `whitespace between every token is allowed`() {
        assertEquals(
            JsonValue.Obj(mapOf("a" to JsonValue.Arr(listOf(JsonValue.Bool(true), JsonValue.Null)))),
            Json.parse("  {\n\t\"a\"  :  [ true , null ]\r\n}  "),
        )
    }

    @Test
    fun `trailing content is a refusal, not a stopping point`() {
        // A reader that stops at the first complete value accepts `{} rm -rf` as `{}`, and what
        // the client wrote and what the server stored then differ without anybody saying so.
        val thrown = assertFailsWith<JsonSyntaxException> { Json.parse("""{"a": 1} and then some""") }

        assertTrue(thrown.message.contains("trailing"), thrown.message)
    }

    @Test
    fun `an unterminated string is a refusal`() {
        assertFailsWith<JsonSyntaxException> { Json.parse("""{"a": "unclosed""") }
    }

    @Test
    fun `a raw control character inside a string is a refusal`() {
        assertFailsWith<JsonSyntaxException> { Json.parse("{\"a\": \"two\nlines\"}") }
    }

    @Test
    fun `an unknown escape is a refusal`() {
        assertFailsWith<JsonSyntaxException> { Json.parse("""{"a": "\q"}""") }
    }

    @Test
    fun `a bare word is not a value`() {
        assertFailsWith<JsonSyntaxException> { Json.parse("""{"a": undefined}""") }
    }

    @Test
    fun `a missing comma is a refusal`() {
        assertFailsWith<JsonSyntaxException> { Json.parse("""{"a": 1 "b": 2}""") }
    }

    /**
     * The one that is about this being a server rather than about JSON: recursive descent turns
     * the client's nesting into our stack, and a `StackOverflowError` is caught by nothing here.
     */
    @Test
    fun `nesting deeper than the cap is refused rather than recursed`() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)

        val thrown = assertFailsWith<JsonSyntaxException> { Json.parse(deep) }

        assertTrue(thrown.message.contains("deeper than"), thrown.message)
    }

    @Test
    fun `nesting up to the cap still reads`() {
        val allowed = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)

        Json.parse(allowed)
    }
}
