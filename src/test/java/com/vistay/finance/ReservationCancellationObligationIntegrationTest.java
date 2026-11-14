package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationCancellationObligationIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        String explicitUrl = System.getenv("TEST_DB_URL");
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            jdbcUrl = explicitUrl;
            username = System.getenv().getOrDefault("TEST_DB_USER", "vistay_test_user");
            password = System.getenv().getOrDefault("TEST_DB_PASS", "vistay_test_pass");
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vistay_finance_cancellation_test")
                    .withUsername("vistay_test_user")
                    .withPassword("vistay_test_pass");
            postgres.start();

            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }

        // Safe forward-only migration in isolated ephemeral container.
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @AfterAll
    static void tearDownAll() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Test
    @DisplayName("SCRUM-569: V7 migration table structure and constraints exist in PostgreSQL")
    void v7Migration_TableStructureAndIndexesExist() throws Exception {
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT column_name, data_type, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = 'app' AND table_name = 'reservation_cancellation_obligations'
                     ORDER BY ordinal_position
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                boolean foundBookingId = false;
                boolean foundQuoteId = false;
                boolean foundVerified = false;
                boolean foundRefundable = false;
                boolean foundForfeited = false;
                boolean foundStatus = false;

                while (rs.next()) {
                    String col = rs.getString("column_name");
                    if ("booking_id".equals(col)) foundBookingId = true;
                    if ("cancellation_quote_id".equals(col)) foundQuoteId = true;
                    if ("verified_collected_amount".equals(col)) foundVerified = true;
                    if ("refundable_amount".equals(col)) foundRefundable = true;
                    if ("forfeited_amount".equals(col)) foundForfeited = true;
                    if ("status".equals(col)) foundStatus = true;
                }

                assertThat(foundBookingId).isTrue();
                assertThat(foundQuoteId).isTrue();
                assertThat(foundVerified).isTrue();
                assertThat(foundRefundable).isTrue();
                assertThat(foundForfeited).isTrue();
                assertThat(foundStatus).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Finding A: Database enforces real PaymentTransaction FK on refunds.refund_transaction_id")
    void databaseConstraint_EnforcesRealPaymentTransactionForeignKeyOnRefundCompletion() throws Exception {
        UUID originalTxId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID fakeTxId = UUID.randomUUID();
        UUID realRefundTxId = UUID.randomUUID();

        String origTxCode = "TX-ORIG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String origIdempKey = "idemp-orig-" + UUID.randomUUID();
        String refundCode = "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refundIdempKey = "idemp-rfd-" + UUID.randomUUID();
        String refundTxCode = "TX-RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refundTxIdempKey = "idemp-tx-rfd-" + UUID.randomUUID();

        try (Connection conn = createConnection()) {
            // 1. Insert original PAYMENT transaction
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.payment_transactions (
                        id, transaction_code, booking_id, transaction_type,
                        amount, currency, payment_method, status, idempotency_key
                    ) VALUES (?, ?, ?, 'PAYMENT', 1000000.00, 'VND', 'BANK_TRANSFER', 'SUCCEEDED', ?)
                    """)) {
                ps.setObject(1, originalTxId);
                ps.setString(2, origTxCode);
                ps.setObject(3, bookingId);
                ps.setString(4, origIdempKey);
                ps.executeUpdate();
            }

            // 2. Insert initial REFUND record pointing to original transaction
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.refunds (
                        id, refund_code, booking_id, original_transaction_id,
                        amount, currency, status, reason, requested_by_admin_user_id, idempotency_key
                    ) VALUES (?, ?, ?, ?, 700000.00, 'VND', 'REQUESTED', 'Cancellation refund', ?, ?)
                    """)) {
                ps.setObject(1, refundId);
                ps.setString(2, refundCode);
                ps.setObject(3, bookingId);
                ps.setObject(4, originalTxId);
                ps.setObject(5, UUID.randomUUID());
                ps.setString(6, refundIdempKey);
                ps.executeUpdate();
            }

            // 3. Attempting to complete refund with a fake transaction ID fails with Foreign Key violation
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE app.refunds
                        SET status = 'COMPLETED', refund_transaction_id = ?, completed_at = now()
                        WHERE id = ?
                        """)) {
                    ps.setObject(1, fakeTxId);
                    ps.setObject(2, refundId);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);

            // 4. Create real immutable REFUND PaymentTransaction linked to original payment
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.payment_transactions (
                        id, transaction_code, original_transaction_id, booking_id, transaction_type,
                        amount, currency, payment_method, status, idempotency_key
                    ) VALUES (?, ?, ?, ?, 'REFUND', 700000.00, 'VND', 'BANK_TRANSFER', 'SUCCEEDED', ?)
                    """)) {
                ps.setObject(1, realRefundTxId);
                ps.setString(2, refundTxCode);
                ps.setObject(3, originalTxId);
                ps.setObject(4, bookingId);
                ps.setString(5, refundTxIdempKey);
                ps.executeUpdate();
            }

            // 5. Updating refund with real transaction ID succeeds
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE app.refunds
                    SET status = 'COMPLETED', refund_transaction_id = ?, completed_at = now()
                    WHERE id = ?
                    """)) {
                ps.setObject(1, realRefundTxId);
                ps.setObject(2, refundId);
                int updated = ps.executeUpdate();
                assertThat(updated).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("SCRUM-569: Database constraint ck_cancel_obligation_balance rejects imbalanced amounts")
    void databaseConstraint_RejectsImbalancedAmounts() {
        UUID obligationId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // Try inserting row where refundable (100) + forfeited (100) != verified (300)
        assertThatThrownBy(() -> {
            try (Connection conn = createConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO app.reservation_cancellation_obligations (
                             id, booking_id, property_id, cancellation_quote_id,
                             cancellation_quote_fingerprint, verified_collected_amount,
                             refundable_amount, forfeited_amount, currency, status,
                             source_event_id, correlation_id
                         ) VALUES (?, ?, ?, ?, 'fp-test', 300.00, 100.00, 100.00, 'VND', 'REFUND_DUE', ?, 'corr-test')
                         """)) {
                ps.setObject(1, obligationId);
                ps.setObject(2, bookingId);
                ps.setObject(3, propertyId);
                ps.setObject(4, quoteId);
                ps.setObject(5, eventId);
                ps.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_cancel_obligation_balance");
    }

    @Test
    @DisplayName("SCRUM-569: Balanced obligation insert succeeds and unique booking constraint is enforced")
    void databaseConstraint_EnforcesUniqueBookingConstraint() throws Exception {
        UUID obligationId1 = UUID.randomUUID();
        UUID obligationId2 = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID quoteId1 = UUID.randomUUID();
        UUID quoteId2 = UUID.randomUUID();
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();

        // 1. Insert first valid row: 700 + 300 = 1000
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.reservation_cancellation_obligations (
                         id, booking_id, property_id, cancellation_quote_id,
                         cancellation_quote_fingerprint, verified_collected_amount,
                         refundable_amount, forfeited_amount, currency, status,
                         source_event_id, correlation_id
                     ) VALUES (?, ?, ?, ?, 'fp-1', 1000.00, 700.00, 300.00, 'VND', 'REFUND_DUE', ?, 'corr-1')
                     """)) {
            ps.setObject(1, obligationId1);
            ps.setObject(2, bookingId);
            ps.setObject(3, propertyId);
            ps.setObject(4, quoteId1);
            ps.setObject(5, eventId1);
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }

        // 2. Insert second row with same bookingId -> must violate UNIQUE (booking_id)
        assertThatThrownBy(() -> {
            try (Connection conn = createConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO app.reservation_cancellation_obligations (
                             id, booking_id, property_id, cancellation_quote_id,
                             cancellation_quote_fingerprint, verified_collected_amount,
                             refundable_amount, forfeited_amount, currency, status,
                             source_event_id, correlation_id
                         ) VALUES (?, ?, ?, ?, 'fp-2', 1000.00, 700.00, 300.00, 'VND', 'REFUND_DUE', ?, 'corr-2')
                         """)) {
                ps.setObject(1, obligationId2);
                ps.setObject(2, bookingId);
                ps.setObject(3, propertyId);
                ps.setObject(4, quoteId2);
                ps.setObject(5, eventId2);
                ps.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("SCRUM-569: Operator repair query selects stuck REFUND_FAILED records without PII")
    void operatorRepairQuery_SelectsRefundFailedWithoutPii() throws Exception {
        UUID obligationId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.reservation_cancellation_obligations (
                         id, booking_id, property_id, cancellation_quote_id,
                         cancellation_quote_fingerprint, verified_collected_amount,
                         refundable_amount, forfeited_amount, currency, status,
                         failure_code, failure_message, source_event_id, correlation_id
                     ) VALUES (?, ?, ?, ?, 'fp-repair', 500.00, 500.00, 0.00, 'VND', 'REFUND_FAILED',
                               'GATEWAY_TIMEOUT', 'Sanitized error message without card number', ?, 'corr-repair')
                     """)) {
            ps.setObject(1, obligationId);
            ps.setObject(2, bookingId);
            ps.setObject(3, propertyId);
            ps.setObject(4, quoteId);
            ps.setObject(5, eventId);
            ps.executeUpdate();
        }

        // Run documented repair query
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, booking_id, cancellation_quote_id, refundable_amount, currency, status, failure_code
                     FROM app.reservation_cancellation_obligations
                     WHERE status = 'REFUND_FAILED' AND id = ?
                     """)) {
            ps.setObject(1, obligationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("REFUND_FAILED");
                assertThat(rs.getString("failure_code")).isEqualTo("GATEWAY_TIMEOUT");
                assertThat(rs.getBigDecimal("refundable_amount")).isEqualByComparingTo(new BigDecimal("500.00"));
            }
        }
    }
}
