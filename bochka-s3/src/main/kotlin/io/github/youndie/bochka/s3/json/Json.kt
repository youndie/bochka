package io.github.youndie.bochka.s3.json

/**
 * A JSON value, as much of one as a bucket policy needs (M-201а).
 *
 * Numbers stay text: nothing in a policy does arithmetic, and parsing `1e400` into a `Double`
 * would turn a document the client wrote into `Infinity` on the way in and something else on the
 * way out. A policy is compared, matched and stored — never computed with.
 */
sealed interface JsonValue {
    data class Str(
        val value: String,
    ) : JsonValue

    data class Num(
        val literal: String,
    ) : JsonValue

    data class Bool(
        val value: Boolean,
    ) : JsonValue

    data object Null : JsonValue

    data class Arr(
        val items: List<JsonValue>,
    ) : JsonValue

    data class Obj(
        val members: Map<String, JsonValue>,
    ) : JsonValue
}

class JsonSyntaxException(
    override val message: String,
) : IllegalArgumentException(message)

/**
 * A strict recursive-descent JSON reader, written here rather than depended on.
 *
 * The project has no `kotlinx-serialization` and this is not the place to acquire one: a policy is
 * a handful of nested objects, and the whole grammar fits in two hundred lines. What it is not is
 * lenient — this text arrives from whoever can reach `PutBucketPolicy`, so trailing content,
 * unknown escapes and unterminated strings are refusals rather than best guesses.
 *
 * **[MAX_DEPTH] is the part that matters for a server.** Recursive descent turns nesting depth
 * into stack depth, so `[[[[…]]]]` from a client is a `StackOverflowError` in a request thread —
 * an error no `catch` in this codebase is written for. Bounded here, at the only place that reads
 * client-supplied JSON.
 */
object Json {
    /** Deeper than any policy AWS documents, shallower than anything that threatens the stack. */
    const val MAX_DEPTH = 32

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value(depth = 0)
        reader.skipSpace()
        if (!reader.done) throw JsonSyntaxException("trailing content at offset ${reader.at}")
        return value
    }

    private class Reader(
        private val text: String,
    ) {
        var at = 0
            private set

        val done get() = at >= text.length

        fun skipSpace() {
            while (at < text.length && text[at].isJsonSpace()) at++
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonSyntaxException("nested deeper than $MAX_DEPTH at offset $at")
            if (done) throw JsonSyntaxException("a value was expected at offset $at")
            return when (text[at]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun obj(depth: Int): JsonValue {
            expect('{')
            val members = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (peek() == '}') {
                at++
                return JsonValue.Obj(members)
            }
            while (true) {
                skipSpace()
                val name = string()
                skipSpace()
                expect(':')
                skipSpace()
                // Last one wins, which is what every JSON reader does with a repeated name. Worth
                // knowing rather than relying on: a policy naming `Effect` twice is legal text.
                members[name] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> {
                        at++
                    }

                    '}' -> {
                        at++
                        return JsonValue.Obj(members)
                    }

                    else -> {
                        throw JsonSyntaxException("',' or '}' was expected at offset $at")
                    }
                }
            }
        }

        private fun arr(depth: Int): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipSpace()
            if (peek() == ']') {
                at++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipSpace()
                items += value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> {
                        at++
                    }

                    ']' -> {
                        at++
                        return JsonValue.Arr(items)
                    }

                    else -> {
                        throw JsonSyntaxException("',' or ']' was expected at offset $at")
                    }
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (done) throw JsonSyntaxException("a string was left unterminated")
                when (val c = text[at++]) {
                    '"' -> {
                        return out.toString()
                    }

                    '\\' -> {
                        out.append(escape())
                    }

                    else -> {
                        // A raw control character is a syntax error in JSON, and letting one
                        // through would put an unescapable byte into a document we echo back.
                        if (c < ' ') throw JsonSyntaxException("a raw control character at offset ${at - 1}")
                        out.append(c)
                    }
                }
            }
        }

        private fun escape(): Char {
            if (done) throw JsonSyntaxException("an escape was left unfinished")
            return when (val c = text[at++]) {
                '"', '\\', '/' -> {
                    c
                }

                'b' -> {
                    '\b'
                }

                'f' -> {
                    '\u000C'
                }

                'n' -> {
                    '\n'
                }

                'r' -> {
                    '\r'
                }

                't' -> {
                    '\t'
                }

                'u' -> {
                    if (at + 4 > text.length) throw JsonSyntaxException("a \\u escape was cut short at offset $at")
                    val digits = text.substring(at, at + 4)
                    at += 4
                    digits.toIntOrNull(16)?.toChar()
                        ?: throw JsonSyntaxException("'$digits' is not four hex digits")
                }

                else -> {
                    throw JsonSyntaxException("unknown escape '\\$c' at offset ${at - 1}")
                }
            }
        }

        private fun number(): JsonValue {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val literal = text.substring(start, at)
            // Checked for shape, kept as text: this is what tells a number from a bare word, which
            // is the only way an unquoted token gets here at all.
            if (literal.isEmpty() || literal.toDoubleOrNull() == null) {
                throw JsonSyntaxException("'$literal' is not a value at offset $start")
            }
            return JsonValue.Num(literal)
        }

        private fun literal(
            word: String,
            value: JsonValue,
        ): JsonValue {
            if (!text.startsWith(word, at)) throw JsonSyntaxException("'$word' was expected at offset $at")
            at += word.length
            return value
        }

        private fun peek(): Char = if (done) throw JsonSyntaxException("the document ended early") else text[at]

        private fun expect(c: Char) {
            if (peek() != c) throw JsonSyntaxException("'$c' was expected at offset $at")
            at++
        }

        private fun Char.isJsonSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r'
    }
}
