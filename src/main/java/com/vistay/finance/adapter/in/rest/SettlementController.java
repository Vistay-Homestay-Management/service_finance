package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.SettlementPeriodService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settlements/periods")
public class SettlementController {

    private final SettlementPeriodService settlementPeriodService;

    public SettlementController(SettlementPeriodService settlementPeriodService) {
        this.settlementPeriodService = settlementPeriodService;
    }

    @PostMapping
    public ResponseEntity<SettlementPeriodResponse> openSettlementPeriod(
            @Valid @RequestBody OpenSettlementPeriodCommand command) {
        SettlementPeriodResponse response = settlementPeriodService.openSettlementPeriod(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SettlementPeriodResponse> getSettlementPeriod(@PathVariable UUID id) {
        return ResponseEntity.ok(settlementPeriodService.getSettlementPeriod(id));
    }

    @PostMapping("/{id}/calculate")
    public ResponseEntity<SettlementPeriodResponse> calculateSettlement(
            @PathVariable UUID id,
            @Valid @RequestBody CalculateSettlementCommand command) {
        return ResponseEntity.ok(settlementPeriodService.calculateSettlement(id, command));
    }

    @PostMapping("/{id}/adjustments")
    public ResponseEntity<SettlementPeriodResponse> addAdjustment(
            @PathVariable UUID id,
            @Valid @RequestBody AddAdjustmentCommand command) {
        return ResponseEntity.ok(settlementPeriodService.addAdjustment(id, command));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<SettlementPeriodResponse> submitForApproval(@PathVariable UUID id) {
        return ResponseEntity.ok(settlementPeriodService.submitForApproval(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<SettlementPeriodResponse> approve(
            @PathVariable UUID id,
            @Valid @RequestBody MakerCheckerApprovalCommand command) {
        return ResponseEntity.ok(settlementPeriodService.approve(id, command));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<SettlementPeriodResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody MakerCheckerApprovalCommand command,
            @RequestParam(required = false, defaultValue = "Rejected by reviewer") String reason) {
        return ResponseEntity.ok(settlementPeriodService.reject(id, command, reason));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<SettlementPeriodResponse> reopen(
            @PathVariable UUID id,
            @RequestParam UUID adminUserId,
            @RequestParam(required = false, defaultValue = "Reopened for correction") String reason) {
        return ResponseEntity.ok(settlementPeriodService.reopen(id, adminUserId, reason));
    }

    @PostMapping("/{id}/payable")
    public ResponseEntity<PayableResponse> createPayable(
            @PathVariable UUID id,
            @RequestParam UUID adminUserId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settlementPeriodService.createPayable(id, adminUserId));
    }

    @PostMapping("/{id}/payout")
    public ResponseEntity<PayableResponse> recordPayout(
            @PathVariable UUID id,
            @Valid @RequestBody RecordPayoutCommand command) {
        return ResponseEntity.ok(settlementPeriodService.recordPayout(id, command));
    }

    @GetMapping("/dashboard/summary")
    public ResponseEntity<FinanceDashboardSummaryResponse> getDashboardSummary() {
        return ResponseEntity.ok(settlementPeriodService.getDashboardSummary());
    }

    @GetMapping("/dashboard/export")
    public ResponseEntity<List<SettlementDashboardExportRow>> exportDashboard(
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate) {
        return ResponseEntity.ok(settlementPeriodService.exportDashboard(fromDate, toDate));
    }
}

