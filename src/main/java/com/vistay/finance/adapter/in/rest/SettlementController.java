package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.SettlementPeriodService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settlements/periods")
public class SettlementController {

    private final SettlementPeriodService settlementPeriodService;
    private final FinanceOperationalAuthorization authorization;

    public SettlementController(
            SettlementPeriodService settlementPeriodService,
            FinanceOperationalAuthorization authorization) {
        this.settlementPeriodService = settlementPeriodService;
        this.authorization = authorization;
    }

    @PostMapping
    public ResponseEntity<SettlementPeriodResponse> openSettlementPeriod(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OpenSettlementPeriodCommand command) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_CREATE);
        var boundCommand = new OpenSettlementPeriodCommand(command.hostId(), command.propertyId(),
                command.startDate(), command.endDate(), command.dueDate(), command.currency(),
                authorization.bindActor(actor, command.openedByAdminUserId()), command.notes());
        SettlementPeriodResponse response = settlementPeriodService.openSettlementPeriod(boundCommand);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SettlementPeriodResponse> getSettlementPeriod(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_READ);
        return ResponseEntity.ok(settlementPeriodService.getSettlementPeriod(id));
    }

    @PostMapping("/{id}/calculate")
    public ResponseEntity<SettlementPeriodResponse> calculateSettlement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody CalculateSettlementCommand command) {
        requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_UPDATE);
        return ResponseEntity.ok(settlementPeriodService.calculateSettlement(id, command));
    }

    @PostMapping("/{id}/adjustments")
    public ResponseEntity<SettlementPeriodResponse> addAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody AddAdjustmentCommand command) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_UPDATE);
        var boundCommand = new AddAdjustmentCommand(command.adjustmentType(), command.amount(),
                command.currency(), command.reason(),
                authorization.bindActor(actor, command.createdByAdminUserId()));
        return ResponseEntity.ok(settlementPeriodService.addAdjustment(id, boundCommand));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<SettlementPeriodResponse> submitForApproval(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_UPDATE);
        return ResponseEntity.ok(settlementPeriodService.submitForApproval(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<SettlementPeriodResponse> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody MakerCheckerApprovalCommand command) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_APPROVE);
        var boundCommand = new MakerCheckerApprovalCommand(
                authorization.bindActor(actor, command.approverAdminUserId()), command.notes());
        return ResponseEntity.ok(settlementPeriodService.approve(id, boundCommand));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<SettlementPeriodResponse> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody MakerCheckerApprovalCommand command,
            @RequestParam(required = false, defaultValue = "Rejected by reviewer") String reason) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_APPROVE);
        var boundCommand = new MakerCheckerApprovalCommand(
                authorization.bindActor(actor, command.approverAdminUserId()), command.notes());
        return ResponseEntity.ok(settlementPeriodService.reject(id, boundCommand, reason));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<SettlementPeriodResponse> reopen(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID adminUserId,
            @RequestParam(required = false, defaultValue = "Reopened for correction") String reason) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_UPDATE);
        return ResponseEntity.ok(settlementPeriodService.reopen(id,
                authorization.bindActor(actor, adminUserId), reason));
    }

    @PostMapping("/{id}/payable")
    public ResponseEntity<PayableResponse> createPayable(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID adminUserId) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_PAYOUT);
        return ResponseEntity.status(HttpStatus.CREATED).body(settlementPeriodService.createPayable(id,
                authorization.bindActor(actor, adminUserId)));
    }

    @PostMapping("/{id}/payout")
    public ResponseEntity<PayableResponse> recordPayout(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody RecordPayoutCommand command) {
        var actor = requireSensitive(jwt, FinanceOperationalAuthorization.SETTLEMENT_PAYOUT);
        var boundCommand = new RecordPayoutCommand(command.amount(), command.paymentReference(),
                command.notes(), authorization.bindActor(actor, command.processedByAdminUserId()));
        return ResponseEntity.ok(settlementPeriodService.recordPayout(id, boundCommand));
    }

    @GetMapping("/dashboard/summary")
    public ResponseEntity<FinanceDashboardSummaryResponse> getDashboardSummary(
            @AuthenticationPrincipal Jwt jwt) {
        authorization.requireAll(jwt, FinanceOperationalAuthorization.FINANCE_READ);
        return ResponseEntity.ok(settlementPeriodService.getDashboardSummary());
    }

    @GetMapping("/dashboard/export")
    public ResponseEntity<List<SettlementDashboardExportRow>> exportDashboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate) {
        authorization.requireAll(jwt, FinanceOperationalAuthorization.SETTLEMENT_EXPORT);
        authorization.requireAll(jwt, FinanceOperationalAuthorization.FINANCE_EXPORT);
        authorization.requireAll(jwt, FinanceOperationalAuthorization.FINANCE_READ);
        return ResponseEntity.ok(settlementPeriodService.exportDashboard(fromDate, toDate));
    }

    private FinanceOperationalAuthorization.Actor requireSensitive(Jwt jwt, String actionPermission) {
        var actor = authorization.requireAll(jwt, actionPermission);
        authorization.requireAll(jwt, FinanceOperationalAuthorization.FINANCE_READ);
        return actor;
    }
}

