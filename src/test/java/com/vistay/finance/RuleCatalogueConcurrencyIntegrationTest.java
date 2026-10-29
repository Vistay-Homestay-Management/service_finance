package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleCatalogueConcurrencyIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("vistay_finance_isolated_test")
                .withUsername("vistay_test_user")
                .withPassword("vistay_test_pass");
        postgres.start();

        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();

        // Safe forward-only migration in isolated ephemeral container. Never clean existing databases.
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
    @DisplayName("SCRUM-317: Concurrent approval with optimistic locking allows exactly one winner")
    void concurrentApproval_OptimisticLockingAllowsExactlyOneWinner() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "CONC-RULE-" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = "ADMIN:MAKER";

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Concurrent Test Rule', 'PRICING', 1, 0, '{}'::jsonb, now(), 'PENDING_APPROVAL', ?)
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            ps.setString(3, creatorId);
            ps.executeUpdate();
        }

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 1; i <= threadCount; i++) {
            final String checkerId = "ADMIN:CHECKER_" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Connection conn = createConnection();
                         PreparedStatement ps = conn.prepareStatement("""
                                 UPDATE app.finance_rule_catalogues
                                 SET status = 'ACTIVE',
                                     approved_by_admin_user_id = ?,
                                     approved_at = now(),
                                     opt_lock_version = opt_lock_version + 1,
                                     updated_at = now()
                                 WHERE id = ? AND opt_lock_version = 0 AND status = 'PENDING_APPROVAL'
                                 """)) {
                        ps.setString(1, checkerId);
                        ps.setObject(2, ruleId);
                        int affected = ps.executeUpdate();
                        if (affected == 1) {
                            successCount.incrementAndGet();
                        } else {
                            conflictCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status, opt_lock_version, approved_by_admin_user_id FROM app.finance_rule_catalogues WHERE id = ?")) {
            ps.setObject(1, ruleId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                assertThat(rs.getLong("opt_lock_version")).isEqualTo(1);
                assertThat(rs.getString("approved_by_admin_user_id")).startsWith("ADMIN:CHECKER_");
            }
        }
    }

    @Test
    @DisplayName("SCRUM-317: Maker-Checker database check constraint prevents creator from approving self")
    void makerCheckerCheckConstraint_RejectsCreatorAsApprover() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "MC-RULE-" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = "ADMIN:SELF_APPROVER";

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Self Approver Test', 'CANCELLATION', 1, 0, '{}'::jsonb, now(), 'PENDING_APPROVAL', ?)
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            ps.setString(3, creatorId);
            ps.executeUpdate();
        }

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE app.finance_rule_catalogues
                     SET approved_by_admin_user_id = ?
                     WHERE id = ?
                     """)) {
            ps.setString(1, creatorId);
            ps.setObject(2, ruleId);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_rule_maker_checker");
        }
    }

    @Test
    @DisplayName("SCRUM-317: Effective dates check constraint prevents effective_to <= effective_from")
    void effectiveDatesCheckConstraint_RejectsInvalidRange() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "DATE-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, effective_to, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Invalid Date Test', 'DEPOSIT', 1, 0, '{}'::jsonb, now(), now() - INTERVAL '1 day', 'DRAFT', 'ADMIN:MAKER')
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_rule_catalogue_dates");
        }
    }

    @Test
    @DisplayName("SCRUM-317: Rule type check constraint rejects unsupported rule types")
    void ruleTypeCheckConstraint_RejectsUnknownType() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "TYPE-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Invalid Type Test', 'UNKNOWN_TYPE', 1, 0, '{}'::jsonb, now(), 'DRAFT', 'ADMIN:MAKER')
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_rule_catalogue_type");
        }
    }

    @Test
    @DisplayName("SCRUM-317: PostgreSQL JSONB check constraint rejects non-object parameter values")
    void jsonbCheckConstraint_RejectsNonObjectParameters() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "JSON-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Invalid Json Test', 'PRICING', 1, 0, '[1, 2, 3]'::jsonb, now(), 'DRAFT', 'ADMIN:MAKER')
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_rule_catalogue_params");
        }
    }

    @Test
    @DisplayName("SCRUM-317: Unique constraint uq_rule_catalogue_code_version prevents duplicate version creation")
    void versioningConstraint_PreventsDuplicateCodeAndVersion() throws Exception {
        UUID ruleId1 = UUID.randomUUID();
        UUID ruleId2 = UUID.randomUUID();
        String ruleCode = "VER-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection()) {
            try (PreparedStatement ps1 = conn.prepareStatement("""
                    INSERT INTO app.finance_rule_catalogues (
                        id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                        parameters_json, effective_from, status, created_by_admin_user_id
                    ) VALUES (?, ?, 'Version 1', 'PRICING', 1, 0, '{}'::jsonb, now(), 'DRAFT', 'ADMIN:MAKER')
                    """)) {
                ps1.setObject(1, ruleId1);
                ps1.setString(2, ruleCode);
                ps1.executeUpdate();
            }

            try (PreparedStatement ps2 = conn.prepareStatement("""
                    INSERT INTO app.finance_rule_catalogues (
                        id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                        parameters_json, effective_from, status, created_by_admin_user_id
                    ) VALUES (?, ?, 'Version 1 duplicate', 'PRICING', 1, 0, '{}'::jsonb, now(), 'DRAFT', 'ADMIN:MAKER')
                    """)) {
                ps2.setObject(1, ruleId2);
                ps2.setString(2, ruleCode);
                assertThatThrownBy(ps2::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("uq_rule_catalogue_code_version");
            }
        }
    }

    @Test
    @DisplayName("SCRUM-317: Exclusion constraint ex_rule_catalogue_active_overlap prevents overlapping ACTIVE rules")
    void exclusionConstraint_PreventsOverlappingActiveRulesForSameCode() throws Exception {
        UUID ruleId1 = UUID.randomUUID();
        UUID ruleId2 = UUID.randomUUID();
        String ruleCode = "OVERLAP-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection()) {
            // Version 1: ACTIVE from 2026-06-01 to 2026-06-30
            try (PreparedStatement ps1 = conn.prepareStatement("""
                    INSERT INTO app.finance_rule_catalogues (
                        id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                        parameters_json, effective_from, effective_to, status, created_by_admin_user_id, approved_by_admin_user_id
                    ) VALUES (?, ?, 'Season 1', 'PRICING', 1, 0, '{}'::jsonb,
                             '2026-06-01 00:00:00+00'::timestamptz, '2026-06-30 00:00:00+00'::timestamptz,
                             'ACTIVE', 'ADMIN:MAKER', 'ADMIN:CHECKER')
                    """)) {
                ps1.setObject(1, ruleId1);
                ps1.setString(2, ruleCode);
                ps1.executeUpdate();
            }

            // Version 2: ACTIVE from 2026-06-15 to 2026-07-15 (overlaps with Version 1)
            try (PreparedStatement ps2 = conn.prepareStatement("""
                    INSERT INTO app.finance_rule_catalogues (
                        id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                        parameters_json, effective_from, effective_to, status, created_by_admin_user_id, approved_by_admin_user_id
                    ) VALUES (?, ?, 'Season 2 Overlapping', 'PRICING', 2, 0, '{}'::jsonb,
                             '2026-06-15 00:00:00+00'::timestamptz, '2026-07-15 00:00:00+00'::timestamptz,
                             'ACTIVE', 'ADMIN:MAKER', 'ADMIN:CHECKER')
                    """)) {
                ps2.setObject(1, ruleId2);
                ps2.setString(2, ruleCode);
                assertThatThrownBy(ps2::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ex_rule_catalogue_active_overlap");
            }
        }
    }

    @Test
    @DisplayName("SCRUM-317: Exclusion constraint allows sequential ACTIVE rules meeting at half-open boundary")
    void exclusionConstraint_AllowsSequentialActiveRulesAtBoundary() throws Exception {
        UUID ruleId1 = UUID.randomUUID();
        UUID ruleId2 = UUID.randomUUID();
        String ruleCode = "TOUCH-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection()) {
            // Version 1: ACTIVE from [2026-06-01, 2026-07-01)
            try (PreparedStatement ps1 = conn.prepareStatement("""
                    INSERT INTO app.finance_rule_catalogues (
                        id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                        parameters_json, effective_from, effective_to, status, created_by_admin_user_id, approved_by_admin_user_id
                    ) VALUES (?, ?, 'Period 1', 'PRICING', 1, 0, '{}'::jsonb,
                             '2026-06-01 00:00:00+00'::timestamptz, '2026-07-01 00:00:00+00'::timestamptz,
                             'ACTIVE', 'ADMIN:MAKER', 'ADMIN:CHECKER')
                    """)) {
                ps1.setObject(1, ruleId1);
                ps1.setString(2, ruleCode);
                int r1 = ps1.executeUpdate();
                assertThat(r1).isEqualTo(1);
            }

            // Version 2: ACTIVE from [2026-07-01, 2026-08-01) - Touches exactly at boundary, does NOT overlap
            try (PreparedStatement ps2 = conn.prepareStatement("""
                    INSERT INTO app.finance_rule_catalogues (
                        id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                        parameters_json, effective_from, effective_to, status, created_by_admin_user_id, approved_by_admin_user_id
                    ) VALUES (?, ?, 'Period 2', 'PRICING', 2, 0, '{}'::jsonb,
                             '2026-07-01 00:00:00+00'::timestamptz, '2026-08-01 00:00:00+00'::timestamptz,
                             'ACTIVE', 'ADMIN:MAKER', 'ADMIN:CHECKER')
                    """)) {
                ps2.setObject(1, ruleId2);
                ps2.setString(2, ruleCode);
                int r2 = ps2.executeUpdate();
                assertThat(r2).isEqualTo(1);
            }
        }
    }
}
