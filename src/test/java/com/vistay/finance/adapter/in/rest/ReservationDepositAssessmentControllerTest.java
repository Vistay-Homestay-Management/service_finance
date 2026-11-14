package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.dto.AssessDepositCommand;
import com.vistay.finance.application.dto.DepositAssessmentResponse;
import com.vistay.finance.application.dto.WaiveDepositCommand;
import com.vistay.finance.application.service.ReservationDepositAssessmentService;
import com.vistay.finance.domain.model.DepositAssessmentStatus;
import com.vistay.finance.domain.model.DepositWaiverMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationDepositAssessmentControllerTest {

    private final FinanceOperationalAuthorization authorization = mock(FinanceOperationalAuthorization.class);
    private final ReservationDepositAssessmentService service = mock(ReservationDepositAssessmentService.class);
    private final Jwt jwt = mock(Jwt.class);

    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @Test
    @DisplayName("Assess endpoint requires finance:deposit:assess and validates scopes via service")
    void assessDeposit_requiresPermissionAndValidatesScopes() {
        var controller = new ReservationDepositAssessmentController(service, authorization);
        var actor = actor(ReservationDepositAssessmentController.DEPOSIT_ASSESS);

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                UUID.randomUUID(),
                new BigDecimal("1000000.0000"),
                "VND",
                "DEP-STD",
                1L,
                "TR-1",
                Instant.now().plusSeconds(3600),
                "idemp-assess-1",
                "corr-1"
        );

        when(authorization.require(jwt, ReservationDepositAssessmentController.DEPOSIT_ASSESS)).thenReturn(actor);
        DepositAssessmentResponse mockResponse = dummyResponse(cmd.targetKey(), DepositAssessmentStatus.PAYMENT_REQUIRED);
        when(service.assessDeposit(eq(cmd), eq(actor))).thenReturn(mockResponse);

        var response = controller.assessDeposit(jwt, cmd);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().targetKey()).isEqualTo(cmd.targetKey());
        // Safe summary redaction verification:
        assertThat(response.getBody().inputFingerprint()).isNull();

        verify(authorization).require(jwt, ReservationDepositAssessmentController.DEPOSIT_ASSESS);
        verify(service).authorizeDepositAccess(actor, propertyId, bookingId);
        verify(service).assessDeposit(cmd, actor);
    }

    @Test
    @DisplayName("Waive endpoint requires finance:deposit:waive, binds actor, and projects safe summary")
    void waiveDeposit_requiresPermissionAndBindsActor() {
        var controller = new ReservationDepositAssessmentController(service, authorization);
        var actor = actor(ReservationDepositAssessmentController.DEPOSIT_WAIVE);

        WaiveDepositCommand input = new WaiveDepositCommand(
                "hold:123",
                null,
                "Customer waiver approved",
                1L,
                0L,
                "idemp-waive-1",
                "corr-waive"
        );

        when(authorization.require(jwt, ReservationDepositAssessmentController.DEPOSIT_WAIVE)).thenReturn(actor);
        when(authorization.bindActor(actor, null)).thenReturn(profileId);

        DepositAssessmentResponse mockResponse = dummyResponse("hold:123", DepositAssessmentStatus.WAIVED_FINAL);
        when(service.waiveDeposit(any(), eq(actor))).thenReturn(mockResponse);

        var response = controller.waiveDeposit(jwt, input);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(DepositAssessmentStatus.WAIVED_FINAL);
        assertThat(response.getBody().waiverApprovedByAdminUserId()).isNull(); // Redacted in safe summary

        var captor = ArgumentCaptor.forClass(WaiveDepositCommand.class);
        verify(service).waiveDeposit(captor.capture(), eq(actor));
        assertThat(captor.getValue().approverAdminId()).isEqualTo(profileId);
    }

    @Test
    @DisplayName("Decision endpoint requires finance:deposit:read and returns active decision")
    void getDecision_requiresPermissionAndReturnsDecision() {
        var controller = new ReservationDepositAssessmentController(service, authorization);
        var actor = actor(ReservationDepositAssessmentController.DEPOSIT_READ);

        DepositAssessmentResponse mockResponse = dummyResponse("booking:" + bookingId, DepositAssessmentStatus.VERIFIED_PAID);
        when(authorization.require(jwt, ReservationDepositAssessmentController.DEPOSIT_READ)).thenReturn(actor);
        when(service.getCurrentDecision("booking:" + bookingId)).thenReturn(mockResponse);

        var response = controller.getDecision(jwt, "booking:" + bookingId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(DepositAssessmentStatus.VERIFIED_PAID);
        assertThat(response.getBody().inputFingerprint()).isNull(); // Redacted in safe summary

        verify(service).authorizeDepositAccess(actor, propertyId, bookingId);
    }

    private FinanceOperationalAuthorization.Actor actor(String permission) {
        Snapshot snapshot = new Snapshot(
                true,
                profileId,
                1L,
                Map.of(permission, Set.of(Scope.ALL)),
                Map.of()
        );
        return new FinanceOperationalAuthorization.Actor(accountId, profileId, permission, snapshot);
    }

    private DepositAssessmentResponse dummyResponse(String targetKey, DepositAssessmentStatus status) {
        return new DepositAssessmentResponse(
                UUID.randomUUID(),
                targetKey,
                bookingId,
                null,
                null,
                propertyId,
                UUID.randomUUID(),
                1L,
                true,
                "dummy-fingerprint",
                null,
                1,
                1L,
                "TR-REF",
                new BigDecimal("500000.0000"),
                BigDecimal.ZERO,
                "VND",
                DepositWaiverMode.NONE,
                "Private waiver reason",
                profileId,
                Instant.now(),
                status,
                Instant.now().plusSeconds(3600),
                "idemp-dummy",
                "corr-dummy",
                0L,
                Instant.now(),
                Instant.now()
        );
    }
}
