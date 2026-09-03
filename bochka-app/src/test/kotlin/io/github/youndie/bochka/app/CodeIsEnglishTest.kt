package io.github.youndie.bochka.app

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Everything that lives in the code is written in English. The rule is in `CLAUDE.md` and predates
 * the first commit; this is what keeps it from drifting back.
 *
 * It drifted once already, and quietly: 1195 lines across 67 files, found while reading an
 * unrelated file rather than by looking for it (M36). A rule with no gate is a preference.
 *
 * **What it checks and what it deliberately cannot.** Prose and data are different things, and only
 * two of the three places prose lives can be told apart from data mechanically:
 *
 * * **comments and KDoc** — checked, including a comment trailing a line of code, which is exactly
 *   where a check written around one shape stops seeing the other;
 * * **backquoted identifiers**, which in this repository means test names — checked. A test name is
 *   what somebody reads in a CI report, and it is all they see when the test fails;
 * * **strings** — *not* checked, and that is the deliberate half. Cyrillic as an object body is
 *   exactly the non-ASCII payload an object store has to be tested with, and a check refusing it
 *   inside a string literal would be wider than the rule it enforces and would take that coverage
 *   away. The cost is that an assertion **message** — prose by any reading — is indistinguishable
 *   from a payload here, so those stay English by review rather than by this.
 *
 * The `ci/` scripts are checked too, in their comments and their Python docstrings, because the
 * same rule names their **output**: what those scripts print goes into a CI log everybody reads.
 * What it cannot see there is the same thing it cannot see in Kotlin — a string literal — and for
 * the same reason.
 *
 * **The version catalog and the deployment manifests are read as well**, and they were not for two
 * milestones after this test was written. Seven Russian lines sat in `gradle/libs.versions.toml`
 * the whole time, in the comments arguing why pitest is run as a CLI and why JUnit is where it is —
 * exactly the kind of prose somebody arriving at this repository reads first. They were found by
 * editing that file for an unrelated reason, which is how the last batch was found too. A gate
 * knows the shapes its author had in front of them; the ones it does not know are silent, and
 * silence here reads as success.
 *
 * The research anchors are exempt because the rule exempts them by name: they are search keys into
 * the Russian documents, and a translated key finds nothing.
 */
class CodeIsEnglishTest {
    @Test
    fun `no Russian prose in the code`() {
        val root = Path.of(System.getProperty("bochka.repoRoot") ?: error("bochka.repoRoot is not set"))
        // What to walk comes from the build, which declares the very same globs as this task's
        // inputs. Held in one place because the two drifted once: this file used to carry its own
        // set of extensions, took every `.py` and `.sh` in the tree, and the build declared only
        // the ones under `ci/` — so Russian in a generator under `docs/spec` left the run green and
        // was visible only under `--rerun-tasks`. A check that stops running exactly when its
        // subject changes reports success, which is worse than not existing.
        val globs =
            (System.getProperty("bochka.guardedSources") ?: error("bochka.guardedSources is not set"))
                .split(" ")
                .filter { it.isNotEmpty() }
                .map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val sources =
            Files.walk(root).use { walk ->
                walk
                    .filter { path -> globs.any { it.matches(root.relativize(path)) } }
                    .filter { path -> path.none { it.toString() == "build" || it.toString() == ".claude" } }
                    .sorted()
                    .toList()
            }
        assertTrue(sources.size > 50, "only ${sources.size} sources under $root; the walk found the wrong tree")

        val offenders = ArrayList<String>()
        for (source in sources) {
            val body = Files.readString(source)
            val found =
                when {
                    source.extension == "kt" || source.extension == "kts" -> prose(body)
                    source.isScopeFile() -> body.lines().mapIndexed { at, line -> (at + 1) to line }
                    source.extension == "yml" || source.extension == "yaml" -> yamlProse(body)
                    else -> scriptProse(body)
                }
            for ((line, text) in found) {
                if (!hasCyrillic(text)) continue
                offenders += "${root.relativize(source)}:$line  ${text.trim().take(70)}"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} lines of Russian in the code:\n" + offenders.joinToString("\n"),
        )
    }

    private companion object {
        /**
         * Anchors into the Russian documents, exempt by the same rule that states them: they are
         * search keys, and a translated key finds nothing.
         *
         * Named one form at a time rather than "anything with a digit next to it". The rule names
         * two of these and the repository grew two more, and each is a **section identifier** of a
         * document that is Russian on purpose — the research sections, a backlog task whose number
         * carries a Cyrillic suffix letter, a numbered risk, a numbered open question. A wider
         * exemption than that would start excusing prose.
         */
        private val ANCHORS =
            listOf(
                Regex("Р\\d+"),
                Regex("M-\\d+[а-я]"),
                Regex("Риск \\d+"),
                Regex("Открытый вопрос \\d+"),
                Regex("Чего чарт не обещает"),
            )
        private val CYRILLIC = Regex("[А-яЁё]")
        private const val TRIPLE = "\"\"\""

        /**
         * The classification files of `ci/`, where a line is prose all the way across: a `#`
         * comment, or a rule whose third column is the reason a run prints into the CI log.
         *
         * Whether those reasons had to be English was the one question M36 left open, and this is
         * what the answer became. Scoped to `ci/` rather than to every `.txt`: a text file
         * elsewhere in this repository is documentation, and documentation is Russian by rule.
         */
        fun Path.isScopeFile() = extension == "txt" && parent?.fileName?.toString() == "ci"

        fun hasCyrillic(text: String) =
            CYRILLIC.containsMatchIn(ANCHORS.fold(text) { rest, anchor -> anchor.replace(rest, "") })

        /**
         * The prose of a YAML file: `#` comments, and the `{{/* … */}}` blocks a Helm template
         * comments itself with.
         *
         * Both shapes, because only the second one had anything in it. A reader written around `#`
         * alone would have walked all 35 of these files, found nothing, and reported that as clean
         * — which is the failure this whole file exists to prevent, arriving through the door it
         * was built to close.
         */
        fun yamlProse(source: String): List<Pair<Int, String>> {
            val found = ArrayList<Pair<Int, String>>()
            var inTemplateComment = false
            for ((offset, line) in source.lines().withIndex()) {
                val opens = line.contains("{{- /*") || line.contains("{{/*")
                if (inTemplateComment || opens || line.trimStart().startsWith("#")) {
                    found += (offset + 1) to line
                }
                if (opens) inTemplateComment = true
                if (line.contains("*/}}")) inTemplateComment = false
            }
            return found
        }

        /**
         * The prose of a shell, Python or TOML file: comment lines, and the triple-quoted blocks a
         * Python file states its purpose in.
         *
         * TOML comes through here because its only comment syntax is `#`. Its own triple quote is a
         * multi-line string rather than a docstring, so a catalog holding one would read as prose
         * from there to its close; there is none, and if there ever is, it will be a value nobody
         * writes prose into.
         */
        fun scriptProse(source: String): List<Pair<Int, String>> {
            val found = ArrayList<Pair<Int, String>>()
            var inDocstring = false
            for ((offset, line) in source.lines().withIndex()) {
                if (inDocstring || line.trimStart().startsWith("#")) {
                    found += (offset + 1) to line
                } else if (line.contains(TRIPLE)) {
                    found += (offset + 1) to line
                }
                // An odd number of triple quotes on a line opens or closes a block; an even number
                // is a docstring that began and ended on one line and changes nothing.
                if (line.windowed(3).count { it == TRIPLE } % 2 == 1) inDocstring = !inDocstring
            }
            return found
        }

        /**
         * Every stretch of prose in a Kotlin source, as line number to text.
         *
         * Hand-written rather than a regular expression, because two of the things being skipped
         * cannot be matched by one: a raw string may hold a comment opener, and **Kotlin's block
         * comments nest** — an opener inside KDoc swallows the rest of the file, which this
         * repository has already been bitten by.
         */
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
        fun prose(source: String): List<Pair<Int, String>> {
            val found = ArrayList<Pair<Int, String>>()
            var at = 0
            var line = 1

            fun peek(offset: Int) = if (at + offset < source.length) source[at + offset] else ' '

            while (at < source.length) {
                when {
                    peek(0) == '/' && peek(1) == '/' -> {
                        val start = at
                        while (at < source.length && source[at] != '\n') at++
                        found += line to source.substring(start, at)
                    }

                    peek(0) == '/' && peek(1) == '*' -> {
                        val start = at
                        val startLine = line
                        var depth = 0
                        while (at < source.length) {
                            if (peek(0) == '/' && peek(1) == '*') {
                                depth++
                                at += 2
                            } else if (peek(0) == '*' && peek(1) == '/') {
                                depth--
                                at += 2
                                if (depth == 0) break
                            } else {
                                if (source[at] == '\n') line++
                                at++
                            }
                        }
                        // Reported line by line: a KDoc block is one comment and twenty places to
                        // fix, and a report naming only where the block started would send the
                        // reader hunting for which of its lines was meant.
                        for ((offset, text) in source.substring(start, at).lines().withIndex()) {
                            found += (startLine + offset) to text
                        }
                    }

                    peek(0) == '`' -> {
                        val start = ++at
                        while (at < source.length && source[at] != '`') {
                            if (source[at] == '\n') line++
                            at++
                        }
                        found += line to source.substring(start, at)
                        at++
                    }

                    peek(0) == '"' && peek(1) == '"' && peek(2) == '"' -> {
                        at += 3
                        while (at < source.length && !(peek(0) == '"' && peek(1) == '"' && peek(2) == '"')) {
                            if (source[at] == '\n') line++
                            at++
                        }
                        at += 3
                    }

                    peek(0) == '"' -> {
                        at++
                        while (at < source.length && source[at] != '"') {
                            if (source[at] == '\\') at++
                            if (at < source.length && source[at] == '\n') line++
                            at++
                        }
                        at++
                    }

                    peek(0) == '\'' -> {
                        at++
                        while (at < source.length && source[at] != '\'') {
                            if (source[at] == '\\') at++
                            at++
                        }
                        at++
                    }

                    else -> {
                        if (source[at] == '\n') line++
                        at++
                    }
                }
            }
            return found
        }
    }
}
