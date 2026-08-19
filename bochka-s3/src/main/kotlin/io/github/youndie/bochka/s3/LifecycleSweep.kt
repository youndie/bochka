package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import java.time.Duration
import java.time.Instant

/**
 * То, ради чего правила вообще принимаются: обход, который удаляет.
 *
 * Конфигурация, которую сервер хранит и отдаёт, но не применяет, — это `PutBucketPolicy` из
 * «чего не делать»: клиент считает правило поставленным, а узнаёт об обратном счётом за хранение,
 * а не ошибкой. Поэтому разбор правил и этот файл — одна веха, и порознь первое было бы вредно.
 *
 * ## Что здесь делается и чего не делается
 *
 * Четыре действия, и все четыре — удаление: текущая версия, неактуальные версии, одинокое
 * надгробие, брошенная многочастная загрузка. Переходов между классами хранения нет, потому что
 * класс один; правило с `<Transition>` до этого места не доходит — оно отвергается на записи.
 *
 * Обход идёт **по публичному API хранилища**, а не по индексу. Не из чистоты: правила — это
 * протокол S3, а `ObjectStore` про S3 не знает и знать не должен, иначе теги, размеры и надгробия
 * придётся объяснять ядру. Цена — лишний проход `versions()` на ключ, и она платится фоновым
 * потоком раз в период.
 */
class LifecycleSweep(
    private val store: ObjectStore,
    private val lifecycles: Lifecycles,
    /**
     * Сколько длится «день» правила.
     *
     * Сутки в поставке. Короче — во встроенном режиме и в прогоне чужого сьюта, где правило
     * «через день» иначе непроверяемо вовсе: тест, ждущий сутки, никто не запустит.
     */
    private val day: Duration = Lifecycle.DAY,
) {
    /** Что обход сделал. Ноль по всем четырём — обычный результат, и печатать его не за чем. */
    data class Report(
        val objects: Int = 0,
        val versions: Int = 0,
        val markers: Int = 0,
        val uploads: Int = 0,
    ) {
        val empty: Boolean get() = objects == 0 && versions == 0 && markers == 0 && uploads == 0

        override fun toString(): String =
            "expired $objects objects, $versions noncurrent versions, $markers delete markers, " +
                "aborted $uploads uploads"
    }

    fun sweep(now: Instant = Instant.now()): Report {
        var report = Report()
        for (bucket in store.bucketNames()) {
            val lifecycle = lifecycles.of(bucket) ?: continue
            val rules = lifecycle.enabled
            if (rules.isEmpty()) continue
            report = report + sweepBucket(bucket, lifecycle, rules, now)
        }
        return report
    }

    private fun sweepBucket(
        bucket: String,
        lifecycle: Lifecycle,
        rules: List<Lifecycle.Rule>,
        now: Instant,
    ): Report {
        var report = Report()
        var marker: ByteArray? = null
        while (true) {
            // Страница нужна только чтобы перечислить **ключи**: версии каждого берутся отдельно
            // и целиком. Иначе продолжение пришлось бы делать внутри ключа — по паре маркеров, —
            // а обход по ходу удаляет, и маркер на удалённую версию не находит ничего. Ту же
            // грабли уже собрала чужая уборка (M-107); здесь она обходится тем, что продолжение
            // всегда стоит на границе ключа.
            val page = store.versionPage(bucket, keyMarker = marker)
            val keys = LinkedHashSet(page.versions.map { it.key })
            for (key in keys) report = report + sweepKey(bucket, lifecycle, rules, key, now)
            if (!page.isTruncated) break
            marker = page.nextKeyMarker ?: break
        }
        return report + sweepUploads(bucket, rules, now)
    }

    private fun sweepKey(
        bucket: String,
        lifecycle: Lifecycle,
        rules: List<Lifecycle.Rule>,
        key: ObjectKey,
        now: Instant,
    ): Report {
        var versions = 0
        // Неактуальные версии первыми, и порядок здесь содержательный: пока под надгробием есть
        // хоть одна версия, оно не одиноко, и правило про одинокое надгробие к нему неприменимо.
        // `test_lifecycle_deletemarker_expiration:9361` проверяет ровно эту последовательность —
        // версия истекает, надгробие остаётся одно, и тогда уходит само.
        val stored = store.versions(bucket, key)
        for ((index, version) in stored.drop(1).withIndex()) {
            val rule =
                rules.firstOrNull {
                    it.noncurrent != null && it.matches(key, version.size, version.metadata.tags)
                } ?: continue
            val noncurrent = rule.noncurrent!!
            // `NewerNoncurrentVersions`: столько самых свежих неактуальных версий переживают срок
            // независимо от возраста. Счёт от текущей вниз, поэтому индекс в списке и есть номер.
            if (noncurrent.newerVersions != null && index < noncurrent.newerVersions) continue
            if (version.lastModified.plus(day.multipliedBy(noncurrent.days.toLong())).isAfter(now)) continue
            if (remove(bucket, key, version.versionId)) versions++
        }

        val remaining = store.versions(bucket, key)
        val current = remaining.firstOrNull() ?: return Report(versions = versions)

        if (!current.deleteMarker) {
            val due =
                lifecycle.expiryOf(key, current.size, current.metadata.tags, current.lastModified, day)
                    ?: return Report(versions = versions)
            if (due.first.isAfter(now)) return Report(versions = versions)
            // `delete`, а не `deleteVersion`: в версионированном бакете истечение срока текущей
            // версии кладёт надгробие и оставляет версию под ним, ровно как обычное удаление.
            // Срок — это не «стереть», это «считать удалённым».
            //
            // И **под условием на ту самую версию**, которую обход посмотрел. Между чтением и
            // удалением клиент успевает записать новую: без условия обход удалил бы свежий
            // объект по сроку, вышедшему у прежнего, — тихо, редко и невоспроизводимо. Условие
            // превращает гонку в пропущенный круг, а следующий круг через секунду.
            return runCatching {
                store.delete(
                    bucket,
                    key,
                    ObjectStore.Precondition(ifMatch = listOf(current.eTag), size = current.size),
                )
            }.fold({ Report(objects = 1, versions = versions) }, { Report(versions = versions) })
        }

        // Одинокое надгробие: под ним не осталось ничего, и оно само есть единственный след
        // ключа. `ExpiredObjectDeleteMarker` снимает его сразу, `Days`/`Date` — по своему сроку.
        if (remaining.size != 1) return Report(versions = versions)
        val rule =
            rules.firstOrNull {
                it.expiration != null && it.matches(key, 0, emptyMap())
            } ?: return Report(versions = versions)
        val expiration = rule.expiration!!
        val due =
            if (expiration.expiredObjectDeleteMarker) {
                current.lastModified
            } else {
                Lifecycle.expiresAt(expiration, current.lastModified, day) ?: return Report(versions = versions)
            }
        if (due.isAfter(now)) return Report(versions = versions)
        return Report(versions = versions, markers = if (remove(bucket, key, current.versionId)) 1 else 0)
    }

    /**
     * Брошенные загрузки — и от фильтра им достаётся только префикс.
     *
     * У начатой загрузки нет ни размера, ни тегов: объекта ещё нет. Правило, называющее тег или
     * размер, до неё поэтому не применяется **вовсе** — а не применяется наполовину. Разница
     * видна на `ObjectSizeLessThan`: незаписанная загрузка «весит» ноль, то есть подошла бы под
     * любой такой порог и была бы отменена по условию, которого никто про неё не проверял.
     */
    private fun sweepUploads(
        bucket: String,
        rules: List<Lifecycle.Rule>,
        now: Instant,
    ): Report {
        var aborted = 0
        for (upload in store.uploads(bucket)) {
            val rule =
                rules.firstOrNull { rule ->
                    rule.abortIncompleteUploadDays != null &&
                        rule.aboutKeysOnly() &&
                        rule.matches(upload.key, 0, emptyMap())
                } ?: continue
            val days = rule.abortIncompleteUploadDays!!
            if (upload.startedAt.plus(day.multipliedBy(days.toLong())).isAfter(now)) continue
            if (store.abortUpload(upload.id)) aborted++
        }
        return Report(uploads = aborted)
    }

    /**
     * Удаление версии, которое молчит о защищённых.
     *
     * Версия под retention или под legal hold не удаляется ни этим обходом, ни чем-либо ещё, и
     * правило жизненного цикла не исключение: замок — это обещание, которое сильнее срока.
     * Отказ здесь не ошибка обхода, поэтому остальные версии он не останавливает.
     */
    private fun remove(
        bucket: String,
        key: ObjectKey,
        versionId: String,
    ): Boolean =
        try {
            store.deleteVersion(bucket, key, versionId) != null
        } catch (_: ObjectStore.Locked) {
            false
        }

    /** Правило, отбирающее только по имени ключа: ни тегов, ни размеров ни на одном уровне. */
    private fun Lifecycle.Rule.aboutKeysOnly(): Boolean {
        val f = filter ?: return true
        if (f.tags.isNotEmpty() || f.sizeGreaterThan != null || f.sizeLessThan != null) return false
        val and = f.and ?: return true
        return and.tags.isEmpty() && and.sizeGreaterThan == null && and.sizeLessThan == null
    }

    private operator fun Report.plus(other: Report): Report =
        Report(
            objects + other.objects,
            versions + other.versions,
            markers + other.markers,
            uploads + other.uploads,
        )
}
