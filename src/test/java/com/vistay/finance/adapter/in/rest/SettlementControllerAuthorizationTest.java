package com.vistay.finance.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.dto.AddAdjustmentCommand;
import com.vistay.finance.application.dto.CalculateSettlementCommand;
import com.vistay.finance.application.dto.MakerCheckerApprovalCommand;
import com.vistay.finance.application.dto.OpenSettlementPeriodCommand;
import com.vistay.finance.application.dto.RecordPayoutCommand;
import com.vistay.finance.application.service.SettlementPeriodService;
import com.vistay.finance.domain.model.AdjustmentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SettlementControllerAuthorizationTest {
    private final SettlementPeriodService service = mock(SettlementPeriodService.class);
    private final FinanceOperationalAuthorization authorization = mock(FinanceOperationalAuthorization.class);
    private final SettlementController controller = new SettlementController(service, authorization);
    private final Jwt jwt = mock(Jwt.class);
    private final UUID profileId = UUID.randomUUID();
    private final UUID periodId = UUID.randomUUID();
    private final FinanceOperationalAuthorization.Actor actor = actor();

    @Test
    void mapsEveryEndpointToExactActionAndSensitiveFieldPermissions() {
        when(authorization.requireAll(eq(jwt), anyString())).thenReturn(actor);
        when(authorization.bindActor(actor, null)).thenReturn(profileId);

        controller.openSettlementPeriod(jwt, openCommand(null));
        controller.getSettlementPeriod(jwt, periodId);
        controller.calculateSettlement(jwt, periodId, new CalculateSettlementCommand(null));
        controller.addAdjustment(jwt, periodId, adjustmentCommand(null));
        controller.submitForApproval(jwt, periodId);
        controller.approve(jwt, periodId, new MakerCheckerApprovalCommand(null, "approved"));
        controller.reject(jwt, periodId, new MakerCheckerApprovalCommand(null, "rejected"), "invalid");
        controller.reopen(jwt, periodId, null, "correction");
        controller.createPayable(jwt, periodId, null);
        controller.recordPayout(jwt, periodId,
            new RecordPayoutCommand(BigDecimal.ONE, "REF", "paid", null));
        controller.getDashboardSummary(jwt);
        controller.exportDashboard(jwt, null, null);

        verify(authorization).requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_CREATE);
        verify(authorization).requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_READ);
        verify(authorization, times(4)).requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_UPDATE);
        verify(authorization, times(2)).requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_APPROVE);
        verify(authorization, times(2)).requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_PAYOUT);
        verify(authorization).requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_EXPORT);
        verify(authorization).requireAll(jwt, FinanceOperationalAuthorization.FINANCE_EXPORT);
        verify(authorization, times(12)).requireAll(jwt, FinanceOperationalAuthorization.FINANCE_READ);
    }

    @Test
    void replacesEveryClientSuppliedActorWithAuthenticatedProfile() {
        when(authorization.requireAll(eq(jwt), anyString())).thenReturn(actor);
        when(authorization.bindActor(actor, null)).thenReturn(profileId);

        controller.openSettlementPeriod(jwt, openCommand(null));
        controller.addAdjustment(jwt, periodId, adjustmentCommand(null));
        controller.approve(jwt, periodId, new MakerCheckerApprovalCommand(null, "approved"));
        controller.recordPayout(jwt, periodId,
            new RecordPayoutCommand(BigDecimal.ONE, "REF", "paid", null));

        var open = ArgumentCaptor.forClass(OpenSettlementPeriodCommand.class);
        var adjustment = ArgumentCaptor.forClass(AddAdjustmentCommand.class);
        var approval = ArgumentCaptor.forClass(MakerCheckerApprovalCommand.class);
        var payout = ArgumentCaptor.forClass(RecordPayoutCommand.class);
        verify(service).openSettlementPeriod(open.capture());
        verify(service).addAdjustment(org.mockito.ArgumentMatchers.eq(periodId), adjustment.capture());
        verify(service).approve(org.mockito.ArgumentMatchers.eq(periodId), approval.capture());
        verify(service).recordPayout(org.mockito.ArgumentMatchers.eq(periodId), payout.capture());

        assertThat(open.getValue().openedByAdminUserId()).isEqualTo(profileId);
        assertThat(adjustment.getValue().createdByAdminUserId()).isEqualTo(profileId);
        assertThat(approval.getValue().approverAdminUserId()).isEqualTo(profileId);
        assertThat(payout.getValue().processedByAdminUserId()).isEqualTo(profileId);
    }

    @Test
    void deniesBeforeMutationWhenActionPermissionIsMissing() {
        when(authorization.requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_CREATE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "missing permission"));

        assertThatThrownBy(() -> controller.openSettlementPeriod(jwt, openCommand(null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service);
    }

    private OpenSettlementPeriodCommand openCommand(UUID actorId) {
        return new OpenSettlementPeriodCommand(UUID.randomUUID(), UUID.randomUUID(),
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
            LocalDate.of(2026, 11, 5), "VND", actorId, "monthly");
    }

    private AddAdjustmentCommand adjustmentCommand(UUID actorId) {
        return new AddAdjustmentCommand(AdjustmentType.MANUAL_CREDIT, BigDecimal.ONE,
            "VND", "correction", actorId);
    }

    private FinanceOperationalAuthorization.Actor actor() {
        var accountId = UUID.randomUUID();
        var snapshot = new Snapshot(true, profileId, 1,
            Map.of("test", Set.of(Scope.ALL)), Map.of());
        return new FinanceOperationalAuthorization.Actor(accountId, profileId, "test", snapshot);
    }
}
