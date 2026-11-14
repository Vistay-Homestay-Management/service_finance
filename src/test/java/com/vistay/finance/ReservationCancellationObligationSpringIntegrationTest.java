package com.vistay.finance;

import tools.jackson.databind.ObjectMapper;
import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.dto.CancellationObligationResponse;
import com.vistay.finance.application.dto.ReconcileRefundOutcomeCommand;
import com.vistay.finance.application.service.ReservationCancellationObligationService;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import com.vistay.finance.domain.repository.ReservationCancellationObligationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest(
    classes = ReservationCancellationObligationSpringIntegrationTest.TestAppConfig.class,
    properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=app",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "grpc.server.port=-1",
        "management.health.ssl.enabled=false",
        "management.endpoints.enabled-by-default=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8080/dummy-certs"
    }
)
class ReservationCancellationObligationSpringIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("vistay_finance_spring_test")
                .withUsername("vistay_test_user")
                .withPassword("vistay_test_pass");
        postgres.start();

        // Safe forward-only migration in isolated ephemeral container.
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        // Create shared canonical finance_command_idempotency table in test database to satisfy Hibernate schema validation
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS app.finance_command_idempotency (
                    idempotency_key varchar(160) PRIMARY KEY,
                    command_type varchar(80) NOT NULL,
                    actor_type varchar(24) NOT NULL,
                    actor_id uuid NOT NULL,
                    request_hash varchar(64) NOT NULL,
                    status varchar(24) NOT NULL,
                    resource_type varchar(64),
                    resource_id uuid,
                    response_payload jsonb,
                    error_code varchar(80),
                    expires_at timestamptz NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    completed_at timestamptz,
                    CONSTRAINT ck_command_idempotency_actor CHECK (actor_type IN ('ADMIN', 'CUSTOMER', 'SYSTEM')),
                    CONSTRAINT ck_command_idempotency_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
                    CONSTRAINT ck_finance_cmd_idem_payload CHECK (response_payload IS NULL OR jsonb_typeof(response_payload) = 'object')
                );
                CREATE INDEX IF NOT EXISTS ix_command_idempotency_expiry ON app.finance_command_idempotency(status, expires_at);
            """);
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.schema", () -> "app");
    }

    @SpringBootApplication(scanBasePackages = "com.vistay.finance")
    static class TestAppConfig {

        @Bean
        @Primary
        public org.springframework.boot.ssl.SslBundles sslBundles() {
            return mock(org.springframework.boot.ssl.SslBundles.class);
        }

        @Bean
        @Primary
        public FinanceOperationalAuthorization financeOperationalAuthorization() {
            return mock(FinanceOperationalAuthorization.class);
        }

        @Bean
        @Primary
        public FinanceAuthorizationClient financeAuthorizationClient() {
            return mock(FinanceAuthorizationClient.class);
        }

        @Bean
        @Primary
        public com.vistay.finance.application.FinanceBookingAuthorizationClient financeBookingAuthorizationClient() {
            return mock(com.vistay.finance.application.FinanceBookingAuthorizationClient.class);
        }

        @Bean
        @Primary
        public com.vistay.finance.application.FinancePropertyPartitionClient financePropertyPartitionClient() {
            return mock(com.vistay.finance.application.FinancePropertyPartitionClient.class);
        }
    }

    @Autowired
    private ReservationCancellationObligationService obligationService;

    @Autowired
    private ReservationCancellationObligationRepository obligationRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private JdbcClient jdbc;

    private final UUID adminProfileId = UUID.randomUUID();
    private final FinanceOperationalAuthorization.Actor actor =
        new FinanceOperationalAuthorization.Actor(UUID.randomUUID(), adminProfileId, "finance:cancellation:assess",
            new FinanceAuthorizationClient.Snapshot(true, adminProfileId, 1L, Map.of(), Map.of()));

    @Test
    @DisplayName("Finding 3.1: Spring IoC cleanly starts up with single @Autowired constructor and mandatory dependencies")
    void springStartup_CleanBeanInjection_ProvesProductionConstructor() {
        assertThat(obligationService).isNotNull();
        assertThat(obligationRepository).isNotNull();
        assertThat(refundRepository).isNotNull();
        assertThat(paymentTransactionRepository).isNotNull();
    }

    @Test
    @DisplayName("Finding 3.1 & 3.4: Real JPA service reconciles refund outcome, commits real PaymentTransaction FK, and supports replay")
    void reconcileRefundOutcome_RealJpaTransactionalCommit_AndReplay() {
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID originalTxId = UUID.randomUUID();
        String externalTxId = "ext-vietqr-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Insert original payment transaction in Postgres via JPA
        PaymentTransaction origTx = new PaymentTransaction(
            originalTxId,
            "TX-ORIG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            null,
            null,
            null,
            bookingId,
            TransactionType.PAYMENT,
            new BigDecimal("1000000.00"),
            "VND",
            PaymentMethod.BANK_TRANSFER,
            null,
            "VIETQR",
            "ext-orig-" + UUID.randomUUID(),
            TransactionStatus.SUCCEEDED,
            "idemp-orig-" + UUID.randomUUID(),
            "Deposit for booking " + bookingId,
            adminProfileId
        );
        paymentTransactionRepository.save(origTx);

        // 2. Insert Refund record pointing to original payment
        Refund refund = new Refund(
            refundId,
            "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            bookingId,
            originalTxId,
            new BigDecimal("700000.00"),
            "VND",
            "Cancellation refund",
            adminProfileId,
            "idemp-rfd-" + UUID.randomUUID()
        );
        refundRepository.save(refund);

        // 3. Insert ReservationCancellationObligation in REFUND_REQUESTED status
        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId,
            bookingId,
            propertyId,
            quoteId,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            null,
            null,
            new BigDecimal("1000000.00"),
            new BigDecimal("700000.00"),
            new BigDecimal("300000.00"),
            "VND",
            null,
            null,
            UUID.randomUUID(),
            "corr-spring-rec"
        );
        obligation.markRefundRequested(refundId);
        obligationRepository.save(obligation);

        // 4. Execute reconcileRefundOutcome via Spring service
        ReconcileRefundOutcomeCommand cmd = new ReconcileRefundOutcomeCommand(
            obligationId,
            refundId,
            true,
            new BigDecimal("700000.00"),
            "VND",
            null,
            null,
            "VIETQR",
            externalTxId,
            "corr-spring-rec"
        );

        CancellationObligationResponse response = obligationService.reconcileRefundOutcome(cmd);

        assertThat(response.status()).isEqualTo(CancellationObligationStatus.REFUNDED.name());

        // 5. Verify real JPA persistence in Postgres:
        // - Refund has status COMPLETED and refundTransactionId is set
        Refund updatedRefund = refundRepository.findById(refundId).orElseThrow();
        assertThat(updatedRefund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(updatedRefund.getRefundTransactionId()).isNotNull();

        // - PaymentTransaction exists with type REFUND, status SUCCEEDED, matching externalTransactionId
        PaymentTransaction refundTx = paymentTransactionRepository.findById(updatedRefund.getRefundTransactionId()).orElseThrow();
        assertThat(refundTx.getTransactionType()).isEqualTo(TransactionType.REFUND);
        assertThat(refundTx.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(refundTx.getAmount()).isEqualByComparingTo(new BigDecimal("700000.00"));
        assertThat(refundTx.getExternalTransactionId()).isEqualTo(externalTxId);
        assertThat(refundTx.getOriginalTransactionId()).isEqualTo(originalTxId);

        // 6. Idempotent replay: calling reconcile again with identical external evidence succeeds with same result
        CancellationObligationResponse replayResponse = obligationService.reconcileRefundOutcome(cmd);
        assertThat(replayResponse.status()).isEqualTo(CancellationObligationStatus.REFUNDED.name());

        // 7. Conflicting replay: calling reconcile with differing externalTransactionId fails closed
        ReconcileRefundOutcomeCommand conflictCmd = new ReconcileRefundOutcomeCommand(
            obligationId,
            refundId,
            true,
            new BigDecimal("700000.00"),
            "VND",
            null,
            null,
            "VIETQR",
            "ext-conflicting-different",
            "corr-spring-rec"
        );
        assertThatThrownBy(() -> obligationService.reconcileRefundOutcome(conflictCmd))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RECONCILIATION_CONFLICT");
    }

    @Test
    @DisplayName("Finding 3.4: Operator repair action retries failed refund in transactional JPA boundary")
    void retryFailedRefund_RealJpaTransactionalRepair() {
        UUID bookingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID originalTxId = UUID.randomUUID();

        // 0. Insert original payment transaction in Postgres via JPA to satisfy FK
        PaymentTransaction origTx = new PaymentTransaction(
            originalTxId,
            "TX-ORIG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            null,
            null,
            null,
            bookingId,
            TransactionType.PAYMENT,
            new BigDecimal("500000.00"),
            "VND",
            PaymentMethod.BANK_TRANSFER,
            null,
            "VIETQR",
            "ext-orig-" + UUID.randomUUID(),
            TransactionStatus.SUCCEEDED,
            "idemp-orig-" + UUID.randomUUID(),
            "Deposit for booking " + bookingId,
            adminProfileId
        );
        paymentTransactionRepository.save(origTx);

        // 1. Setup existing records in FAILED state
        Refund refund = new Refund(
            refundId,
            "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            bookingId,
            originalTxId,
            new BigDecimal("500000.00"),
            "VND",
            "Failed refund",
            adminProfileId,
            "idemp-rfd-fail-" + UUID.randomUUID()
        );
        refund.markFailed();
        refundRepository.save(refund);

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId,
            bookingId,
            propertyId,
            quoteId,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            null,
            null,
            new BigDecimal("500000.00"),
            new BigDecimal("500000.00"),
            BigDecimal.ZERO,
            "VND",
            null,
            null,
            UUID.randomUUID(),
            "corr-spring-retry"
        );
        obligation.markRefundRequested(refundId);
        obligation.markRefundFailed("PROVIDER_TIMEOUT", "Gateway timed out");
        obligationRepository.save(obligation);

        // 2. Invoke operator retry via service
        CancellationObligationResponse response =
            obligationService.retryFailedRefund(obligationId, "corr-retry-op", actor);

        assertThat(response.status()).isEqualTo(CancellationObligationStatus.REFUND_REQUESTED.name());

        // 3. Verify database state
        Refund retriedRefund = refundRepository.findById(refundId).orElseThrow();
        assertThat(retriedRefund.getStatus()).isEqualTo(RefundStatus.REQUESTED);

        ReservationCancellationObligation retriedObligation = obligationRepository.findById(obligationId).orElseThrow();
        assertThat(retriedObligation.getStatus()).isEqualTo(CancellationObligationStatus.REFUND_REQUESTED);
        assertThat(retriedObligation.getFailureCode()).isNull();

        // 4. Verify outbox event was recorded in Postgres
        Integer count = jdbc.sql("""
                SELECT count(*) FROM app.outbox_events
                WHERE event_type = 'vistay.finance.cancellation-refund-retry-requested.v1'
                AND aggregate_id = :aggregateId
                """)
            .param("aggregateId", obligationId)
            .query(Integer.class)
            .single();
        assertThat(count).isEqualTo(1);
    }
}
