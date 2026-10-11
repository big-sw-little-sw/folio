package io.github.big_sw_little_sw.folio.web.internal

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Exports spans with exception types, but without exception messages (design 15.1). An observation that ends with an
 * error records an `exception` event with the message and the stack trace, and sets the message as the status
 * description. A message can contain values that spans must never contain, for example a slug in a database error. The
 * first line of a stack trace repeats the message. Thus [delegate] gets each `exception` event with its type only, and
 * the error status without a description. Folio's logs follow the same rule for Git errors (design 10.4).
 */
class TypeOnlyExceptionExporter(private val delegate: SpanExporter) : SpanExporter by delegate {
    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        delegate.export(spans.map(::withoutMessages))

    private fun withoutMessages(span: SpanData): SpanData = object : DelegatingSpanData(span) {
        override fun getEvents(): List<EventData> = span.events.map(::typeOnly)

        override fun getStatus(): StatusData =
            if (span.status.statusCode == StatusCode.ERROR) StatusData.error() else span.status
    }

    private fun typeOnly(event: EventData): EventData {
        if (event.name != EXCEPTION) return event
        val type =
            event.attributes
                .toBuilder()
                .removeIf { it.key != EXCEPTION_TYPE }
                .build()
        return EventData.create(event.epochNanos, event.name, type)
    }

    private companion object {
        const val EXCEPTION = "exception"
        const val EXCEPTION_TYPE = "exception.type"
    }
}
