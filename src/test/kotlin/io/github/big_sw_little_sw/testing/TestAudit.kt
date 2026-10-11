package io.github.big_sw_little_sw.testing

import org.springframework.boot.test.context.TestComponent
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.*

/**
 * This component reads audit records. Folio has no API to read the records, so the tests query the table. Test classes
 * share the database, so each test reads only the records of its own resources, or counts the records of an operation
 * before and after.
 */
@TestComponent
class TestAudit(private val jdbc: JdbcClient, private val mapper: JsonMapper) {
    /** Returns the records of the resource in the order of their writes. */
    fun records(resourceId: UUID): List<Map<String, Any?>> = jdbc
        .sql("select * from audit_event where resource_id = :id order by id")
        .param("id", resourceId)
        .query()
        .listOfRows()

    /** Returns the records of the operation in the order of their writes. */
    fun records(operation: String): List<Map<String, Any?>> = jdbc
        .sql("select * from audit_event where operation = :operation order by id")
        .param("operation", operation)
        .query()
        .listOfRows()

    /** Expects the resource to have records of exactly these [operations], in this order. */
    fun assertTrail(resourceId: UUID, vararg operations: String) {
        assertEquals(operations.toList(), records(resourceId).map { it["operation"] })
    }

    /** Expects the JSON [details] in [record]. `jsonb` does not keep the key order, so this compares JSON trees. */
    fun assertDetails(details: String, record: Map<String, Any?>) {
        assertEquals(json(details), json(record["details"]))
    }

    /** Parses a JSON value, so that a test can compare JSON trees and not strings. */
    fun json(value: Any?): JsonNode = mapper.readTree(value.toString())
}
