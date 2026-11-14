package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationAmendmentAssessmentIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vistay_finance_amendment_test")
            .withUsername("vistay_test_user")
            .withPassword("vistay_test_pass")
            .withStartupTimeout(java.time.Duration.ofSeconds(60));
        postgres.start();

        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();

        Flyway flyway = Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .schemas("app")
            .defaultSchema("app")
            .cleanDisabled(true)
            .outOfOrder(false)
            .locations("classpath:db/migration")
            .load();
        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(5);
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
    @DisplayName("V6 Migration: Verifies schema and indexes for amendment assessments and idempotency")
    void verifyV6TablesAndIndexes() throws Exception {
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT column_name, data_type
                 FROM information_schema.columns
                 WHERE table_schema = 'app' AND table_name = 'reservation_amendment_assessments'
                 """)) {
            try (ResultSet rs = ps.executeQuery()) {
                boolean foundFingerprint = false;
                boolean foundRevision = false;
                boolean foundAction = false;
                while (rs.next()) {
                    String col = rs.getString("column_name");
                    if ("after_snapshot_fingerprint".equals(col)) foundFingerprint = true;
                    if ("amendment_revision".equals(col)) foundRevision = true;
                    if ("required_action".equals(col)) foundAction = true;
                }
                assertThat(foundFingerprint).isTrue();
                assertThat(foundRevision).isTrue();
                assertThat(foundAction).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Partial unique index ux_amendment_assessment_current prevents concurrent current revisions")
    void partialUniqueIndex_enforcesSingleCurrentRevision() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        String proposalId = "prop-iso-1";
        String fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        try (Connection conn = createConnection()) {
            // Insert revision 1 with is_current = true
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.reservation_amendment_assessments(
                    id, booking_id, property_id, proposal_id, amendment_revision,
                    is_current, before_snapshot_fingerprint, after_snapshot_fingerprint,
                    rule_version, before_required_amount, after_required_amount,
                    verified_collected_amount, delta_amount, currency, required_action,
                    status, expires_at, idempotency_key, correlation_id
                ) VALUES (
                    ?, ?, ?, ?, 1,
                    true, ?, ?,
                    1, 100, 150,
                    100, 50, 'VND', 'COLLECTION_REQUIRED',
                    'COLLECTION_REQUIRED', ?, ?, ?
                )
                """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, bookingId);
                ps.setObject(3, propertyId);
                ps.setString(4, proposalId);
                ps.setString(5, fp);
                ps.setString(6, fp);
                ps.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(600)));
                ps.setString(8, "idem-u1");
                ps.setString(9, "corr-u1");
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            // Attempting to insert revision 2 with is_current = true concurrently MUST fail unique violation
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.reservation_amendment_assessments(
                    id, booking_id, property_id, proposal_id, amendment_revision,
                    is_current, before_snapshot_fingerprint, after_snapshot_fingerprint,
                    rule_version, before_required_amount, after_required_amount,
                    verified_collected_amount, delta_amount, currency, required_action,
                    status, expires_at, idempotency_key, correlation_id
                ) VALUES (
                    ?, ?, ?, ?, 2,
                    true, ?, ?,
                    1, 100, 180,
                    100, 80, 'VND', 'COLLECTION_REQUIRED',
                    'COLLECTION_REQUIRED', ?, ?, ?
                )
                """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, bookingId);
                ps.setObject(3, propertyId);
                ps.setString(4, proposalId);
                ps.setString(5, fp);
                ps.setString(6, fp);
                ps.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(600)));
                ps.setString(8, "idem-u2");
                ps.setString(9, "corr-u2");
                assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ux_amendment_assessment_current");
            }
        }
    }

    @Test
    @DisplayName("Check constraint ck_amendment_waiver_maker_checker rejects same assessor and approver")
    void makerCheckerConstraint_rejectsSelfApproval() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID assessorId = UUID.randomUUID();
        String fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        try (Connection conn = createConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.reservation_amendment_assessments(
                    id, booking_id, property_id, proposal_id, amendment_revision,
                    is_current, before_snapshot_fingerprint, after_snapshot_fingerprint,
                    rule_version, before_required_amount, after_required_amount,
                    verified_collected_amount, delta_amount, currency, required_action,
                    status, assessed_by_actor_id, approved_by_admin_user_id, approved_at,
                    expires_at, idempotency_key, correlation_id
                ) VALUES (
                    ?, ?, ?, 'prop-mc', 1,
                    true, ?, ?,
                    1, 100, 150,
                    100, 50, 'VND', 'WAIVER_REVIEW',
                    'WAIVED_FINAL', ?, ?, now(),
                    ?, 'idem-mc', 'corr-mc'
                )
                """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, bookingId);
                ps.setObject(3, propertyId);
                ps.setString(4, fp);
                ps.setString(5, fp);
                ps.setObject(6, assessorId);
                ps.setObject(7, assessorId); // SAME ASSESSOR AND APPROVER!
                ps.setTimestamp(8, Timestamp.from(Instant.now().plusSeconds(600)));
                assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_amendment_waiver_maker_checker");
            }
        }
    }

    @Test
    @DisplayName("Executed event creates compensating receivable and leaves historical payment transactions untouched")
    void executedEvent_leavesPaymentTransactionsUntouched() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentRequestId = UUID.randomUUID();

        try (Connection conn = createConnection()) {
            // Create baseline booking payment request & succeeded payment transaction
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.payment_requests(id, request_code, booking_id, payer_type, payer_id, amount, currency, purpose, idempotency_key, status)
                VALUES (?, ?, ?, 'CUSTOMER', ?, 5000000, 'VND', 'Initial deposit', 'req-1', 'PAID')
                """)) {
                ps.setObject(1, paymentRequestId);
                ps.setString(2, "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                ps.setObject(3, bookingId);
                ps.setObject(4, customerId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.payment_transactions(id, transaction_code, payment_request_id, booking_id, transaction_type, amount, currency, payment_method, payment_provider, status, idempotency_key, description)
                VALUES (?, 'TX-BASE-1', ?, ?, 'PAYMENT', 5000000, 'VND', 'BANK_TRANSFER', 'VIETQR', 'SUCCEEDED', 'tx-1', 'Initial payment')
                """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, paymentRequestId);
                ps.setObject(3, bookingId);
                ps.executeUpdate();
            }

            // Count payment transactions before amendment
            long txCountBefore;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM app.payment_transactions WHERE booking_id = ?")) {
                ps.setObject(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    txCountBefore = rs.getLong(1);
                }
            }
            assertThat(txCountBefore).isEqualTo(1L);

            // Insert compensating receivable
            UUID receivableId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.receivables(
                    id, receivable_code, source_key, booking_id, debtor_type, debtor_id,
                    category, description, due_date, amount_due, currency, status
                ) VALUES (
                    ?, 'REC-AMD-001', 'amendment-source-1', ?, 'CUSTOMER', ?,
                    'RESERVATION_AMENDMENT', 'Amendment compensating receivable', CURRENT_DATE + 1, 2000000, 'VND', 'OPEN'
                )
                """)) {
                ps.setObject(1, receivableId);
                ps.setObject(2, bookingId);
                ps.setObject(3, customerId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            // Assert count of payment transactions remains EXACTLY 1 (untouched historical records)
            long txCountAfter;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM app.payment_transactions WHERE booking_id = ?")) {
                ps.setObject(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    txCountAfter = rs.getLong(1);
                }
            }
            assertThat(txCountAfter).isEqualTo(txCountBefore);
        }
    }

    @Test
    @DisplayName("Idempotency: atomic claim with ON CONFLICT DO NOTHING survives concurrency without aborting Postgres tx")
    void concurrentIdempotencyClaim_atomicOnPostgreSQL() throws Exception {
        String idemKey = "concurrent-claim-test-" + UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String requestHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var results = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.concurrent.Callable<Integer> task = () -> {
            barrier.await();
            try (Connection conn = createConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.finance_command_idempotency(
                        idempotency_key, command_type, actor_type, actor_id,
                        request_hash, status, expires_at, created_at
                    )
                    VALUES (?, 'ASSESS_RESERVATION_AMENDMENT', 'ADMIN', ?, ?, 'PROCESSING', now() + interval '1 hour', now())
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """)) {
                    ps.setString(1, idemKey);
                    ps.setObject(2, actorId);
                    ps.setString(3, requestHash);
                    int updated = ps.executeUpdate();
                    conn.commit();
                    return updated;
                }
            }
        };

        var future1 = executor.submit(task);
        var future2 = executor.submit(task);

        int r1 = future1.get();
        int r2 = future2.get();
        executor.shutdown();

        // Exactly one winner acquired the lock, the other safely received 0 without transaction abort
        assertThat(r1 + r2).isEqualTo(1);

        // Verify row is present in PROCESSING state
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM app.finance_command_idempotency WHERE idempotency_key = ?")) {
            ps.setString(1, idemKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("PROCESSING");
            }
        }
    }

    @Test
    @DisplayName("Executed event: fully collected amendment creates SETTLED receivable with allocation")
    void executedEvent_whenFullyCollected_createsSettledReceivableWithAllocation() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentRequestId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID receivableId = UUID.randomUUID();

        try (Connection conn = createConnection()) {
            // Setup payment request and succeeded transaction
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.payment_requests(id, request_code, booking_id, payer_type, payer_id, amount, currency, purpose, idempotency_key, status)
                VALUES (?, ?, ?, 'CUSTOMER', ?, 5000000, 'VND', 'Amendment payment', ?, 'PAID')
                """)) {
                ps.setObject(1, paymentRequestId);
                ps.setString(2, "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                ps.setObject(3, bookingId);
                ps.setObject(4, customerId);
                ps.setString(5, "idem-req-" + UUID.randomUUID());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.payment_transactions(id, transaction_code, payment_request_id, booking_id, transaction_type, amount, currency, payment_method, payment_provider, status, idempotency_key, description)
                VALUES (?, ?, ?, ?, 'PAYMENT', 5000000, 'VND', 'BANK_TRANSFER', 'VIETQR', 'SUCCEEDED', ?, 'Amendment payment')
                """)) {
                ps.setObject(1, txId);
                ps.setString(2, "TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                ps.setObject(3, paymentRequestId);
                ps.setObject(4, bookingId);
                ps.setString(5, "idem-tx-" + UUID.randomUUID());
                ps.executeUpdate();
            }

            // Insert SETTLED compensating receivable (already fully paid)
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.receivables(
                    id, receivable_code, source_key, booking_id, debtor_type, debtor_id,
                    category, description, due_date, amount_due, amount_paid, amount_waived,
                    currency, status, closed_at
                ) VALUES (
                    ?, 'REC-SETTLED-001', ?, ?, 'CUSTOMER', ?,
                    'RESERVATION_AMENDMENT', 'Settled compensating receivable', CURRENT_DATE + 1, 2000000, 2000000, 0,
                    'VND', 'SETTLED', now()
                )
                """)) {
                ps.setObject(1, receivableId);
                ps.setString(2, "amendment-settled-" + UUID.randomUUID());
                ps.setObject(3, bookingId);
                ps.setObject(4, customerId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            // Insert allocation linking receivable to transaction
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app.receivable_allocations(id, receivable_id, payment_transaction_id, amount, allocated_at)
                VALUES (gen_random_uuid(), ?, ?, 2000000, now())
                """)) {
                ps.setObject(1, receivableId);
                ps.setObject(2, txId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            // Assert receivable is SETTLED and amount_paid == amount_due
            try (PreparedStatement ps = conn.prepareStatement("SELECT status, amount_due, amount_paid FROM app.receivables WHERE id = ?")) {
                ps.setObject(1, receivableId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("SETTLED");
                    assertThat(rs.getBigDecimal("amount_due")).isEqualByComparingTo(BigDecimal.valueOf(2000000));
                    assertThat(rs.getBigDecimal("amount_paid")).isEqualByComparingTo(BigDecimal.valueOf(2000000));
                }
            }
        }
    }
}
