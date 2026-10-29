package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.*;
import java.time.Instant;
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
        if (isReachable("127.0.0.1", 5433)) {
            ensureDatabaseExists("127.0.0.1", 5433, "vistay_finance_test", "vistay", "vistay");
            jdbcUrl = "jdbc:postgresql://127.0.0.1:5433/vistay_finance_test";
            username = "vistay";
            password = "vistay";
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vistay_finance_test")
                    .withUsername("vistay")
                    .withPassword("vistay");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(false)
                .outOfOrder(true)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureDatabaseExists(String host, int port, String dbName, String user, String pass) {
        String adminUrl = "jdbc:postgresql://" + host + ":" + port + "/postgres";
        try (Connection conn = DriverManager.getConnection(adminUrl, user, pass);
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");
            if (!rs.next()) {
                st.executeUpdate("CREATE DATABASE " + dbName);
            }
        } catch (Exception ignored) {
        }
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
    @DisplayName("Concurrent approval with optimistic locking allows exactly one winner")
    void concurrentApproval_OptimisticLockingAllowsExactlyOneWinner() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "CONC-RULE-" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = "ADMIN:MAKER";

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Concurrent Test Rule', 'PRICING', 1, 0, '{}', now(), 'PENDING_APPROVAL', ?)
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
    @DisplayName("Maker-Checker database check constraint prevents creator from approving self")
    void makerCheckerCheckConstraint_RejectsCreatorAsApprover() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "MC-RULE-" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = "ADMIN:SELF_APPROVER";

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Self Approver Test', 'CANCELLATION', 1, 0, '{}', now(), 'PENDING_APPROVAL', ?)
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
    @DisplayName("Effective dates check constraint prevents effective_to before effective_from")
    void effectiveDatesCheckConstraint_RejectsInvalidRange() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "DATE-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, effective_to, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Invalid Date Test', 'DEPOSIT', 1, 0, '{}', now(), now() - INTERVAL '1 day', 'DRAFT', 'ADMIN:MAKER')
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_rule_catalogue_dates");
        }
    }

    @Test
    @DisplayName("Rule type check constraint rejects unsupported rule types")
    void ruleTypeCheckConstraint_RejectsUnknownType() throws Exception {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "TYPE-RULE-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.finance_rule_catalogues (
                         id, rule_code, rule_name, rule_type, rule_version, opt_lock_version,
                         parameters_json, effective_from, status, created_by_admin_user_id
                     ) VALUES (?, ?, 'Invalid Type Test', 'UNKNOWN_TYPE', 1, 0, '{}', now(), 'DRAFT', 'ADMIN:MAKER')
                     """)) {
            ps.setObject(1, ruleId);
            ps.setString(2, ruleCode);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_rule_catalogue_type");
        }
    }
}
