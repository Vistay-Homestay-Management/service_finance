package com.vistay.finance.adapter.in.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentConfirmedConsumer {
    private static final String CONSUMER = "finance-payment-confirmed-v1";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PaymentConfirmedConsumer(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "vistay.payment.transaction-confirmed.v1")
    @Transactional
    public void consume(String payload) {
        var root = objectMapper.readTree(payload);
        requireText(root, "type", "vistay.payment.transaction-confirmed");
        if (root.path("version").asInt() != 1) throw new IllegalArgumentException("Unsupported payment event version");
        var eventId = uuid(root, "eventId");
        int inserted = jdbc.sql("""
                INSERT INTO app.processed_events(event_id, consumer_name)
                VALUES (:eventId, :consumer)
                ON CONFLICT DO NOTHING
                """)
            .param("eventId", eventId).param("consumer", CONSUMER).update();
        if (inserted == 0) return;

        var data = root.required("data");
        var financeRequestId = uuid(data, "financePaymentRequestId");
        var providerTransactionId = uuid(data, "providerTransactionId");
        var amount = new BigDecimal(data.required("amount").asText());
        var currency = data.required("currency").asText();
        var referenceNumber = data.required("referenceNumber").asText();
        var occurredAt = Instant.parse(data.required("occurredAt").asText());
        requireText(data, "matchStatus", "MATCHED");

        var request = jdbc.sql("""
                SELECT booking_id, amount, currency, status
                FROM app.payment_requests
                WHERE id = :id
                FOR UPDATE
                """)
            .param("id", financeRequestId)
            .query(PaymentRequestRow.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Finance payment request not found"));
        if (request.amount().compareTo(amount) != 0 || !request.currency().equals(currency)) {
            throw new IllegalArgumentException("Confirmed payment differs from Finance obligation");
        }

        jdbc.sql("""
                INSERT INTO app.payment_transactions(
                    transaction_code, payment_request_id, booking_id, transaction_type,
                    amount, currency, payment_method, payment_provider,
                    external_transaction_id, status, occurred_at, completed_at,
                    idempotency_key, description)
                VALUES (
                    :code, :requestId, :bookingId, 'PAYMENT',
                    :amount, :currency, 'BANK_TRANSFER', 'VIETQR',
                    :externalId, 'SUCCEEDED', :occurredAt, :occurredAt,
                    :idempotencyKey, 'VietQR payment confirmed')
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .param("code", "VQ" + providerTransactionId.toString().replace("-", "").toUpperCase())
            .param("requestId", financeRequestId)
            .param("bookingId", request.bookingId())
            .param("amount", amount)
            .param("currency", currency)
            .param("externalId", referenceNumber)
            .param("occurredAt", occurredAt)
            .param("idempotencyKey", "payment-confirmed:" + providerTransactionId)
            .update();
        jdbc.sql("""
                UPDATE app.payment_requests
                SET status = 'PAID', updated_at = now(), version = version + 1
                WHERE id = :id AND status IN ('PENDING', 'PROCESSING', 'PARTIALLY_PAID')
                """)
            .param("id", financeRequestId).update();
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.required(field).asText());
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.required(field).asText())) {
            throw new IllegalArgumentException("Unexpected " + field);
        }
    }

    private record PaymentRequestRow(UUID bookingId, BigDecimal amount, String currency, String status) {}
}
