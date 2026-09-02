package io.github.youndie.bochka.http

/**
 * A client that stopped sending: not this server's failure, and answered `408` rather than `500`.
 *
 * A distinct type because the session has to tell it apart from a handler blowing up — the first is
 * the client's timing and the second is our bug, and telling a client to retry something it never
 * finished sending is how a stalled upload becomes a storm.
 */
class RequestTimeout(
    message: String,
) : Exception(message)
