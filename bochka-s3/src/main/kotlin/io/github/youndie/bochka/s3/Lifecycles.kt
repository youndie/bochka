package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.s3.xml.S3Requests
import java.util.concurrent.ConcurrentHashMap

/**
 * Правила бакета, разобранные один раз на документ.
 *
 * Ядро хранит настройку байтами и не знает, что означает `lifecycle` — это его решение, и оно
 * правильное. Цена решения в том, что каждый, кому нужны правила, разбирает XML; а нужны они
 * **на пути чтения**: `x-amz-expiration` считается на каждый `GET` и `HEAD`.
 *
 * Отсюда кэш, и ключ у него не имя бакета, а сам массив байтов — по ссылке. Хранилище отдаёт
 * ту же ссылку, пока настройку не переписали, поэтому `===` отвечает ровно на нужный вопрос
 * («это тот же документ, что я разбирал») и не требует ни версии, ни сброса, ни отдельного
 * оповещения при записи. Сравнение по содержимому стоило бы обхода документа, то есть примерно
 * того же, что и разбор.
 */
class Lifecycles(
    private val store: ObjectStore,
) {
    private val parsed = ConcurrentHashMap<String, Pair<ByteArray, Lifecycle>>()

    /** Правила бакета или `null`, если их нет. */
    fun of(bucket: String): Lifecycle? {
        val document = store.bucketSubresource(bucket, NAME) ?: return null
        parsed[bucket]?.let { (from, lifecycle) -> if (from === document) return lifecycle }
        // Документ в журнале отрисован этим же сервером, так что разобраться он обязан. Обязан —
        // не значит «разберётся»: журнал переживает обновления, и правило, которое сегодня
        // отвергается, могло быть записано вчерашней версией. Бакет без правил лучше, чем
        // хранилище, которое не отвечает ни на один запрос к нему.
        val lifecycle = runCatching { S3Requests.parseLifecycle(document) }.getOrNull() ?: return null
        parsed[bucket] = document to lifecycle
        return lifecycle
    }

    companion object {
        /** Имя настройки в хранилище, оно же имя подресурса в запросе. */
        const val NAME: String = "lifecycle"
    }
}
