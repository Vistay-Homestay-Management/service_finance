package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.PayableRepository;
import com.vistay.finance.domain.repository.SettlementAdjustmentRepository;
import com.vistay.finance.domain.repository.SettlementLineRepository;
import com.vistay.finance.domain.repository.SettlementPeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementPeriodService {

    private final SettlementPeriodRepository periodRepository;
    private final SettlementLineRepository lineRepository;
    private final SettlementAdjustmentRepository adjustmentRepository;
    private final PayableRepository payableRepository;

    public SettlementPeriodService(SettlementPeriodRepository periodRepository,
                                   SettlementLineRepository lineRepository,
                                   SettlementAdjustmentRepository adjustmentRepository,
                                   PayableRepository payableRepository) {
        this.periodRepository = periodRepository;
        this.lineRepository = lineRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.payableRepository = payableRepository;
    }

    @Transactional
    public SettlementPeriodResponse openSettlementPeriod(OpenSettlementPeriodCommand command) {
        if (command.hostId() == null) {
            throw new IllegalArgumentException("hostId cannot be null");
        }
        if (command.startDate() == null || command.endDate() == null || command.dueDate() == null) {
            throw new IllegalArgumentException("startDate, endDate, and dueDate cannot be null");
        }

        String yearMonth = command.startDate().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String periodCode = "SETTLE-" + yearMonth + "-" + suffix;

        SettlementPeriod period = new SettlementPeriod(
                periodCode,
                command.hostId(),
                command.propertyId(),
                command.startDate(),
                command.endDate(),
                command.dueDate(),
                command.currency(),
                command.openedByAdminUserId(),
                command.notes()
        );

        SettlementPeriod saved = periodRepository.save(period);
        return SettlementPeriodResponse.from(saved, List.of(), List.of());
    }

    @Transactional
    public SettlementPeriodResponse calculateSettlement(UUID periodId, CalculateSettlementCommand command) {
        SettlementPeriod period = findPeriodOrThrow(periodId);

        if (period.getStatus() == SettlementStatus.APPROVED || period.getStatus() == SettlementStatus.PAID) {
            throw new IllegalStateException("Cannot recalculate an APPROVED or PAID settlement period");
        }

        lineRepository.deleteBySettlementPeriodId(periodId);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalCleaning = BigDecimal.ZERO;

        if (command.bookingItems() != null) {
            for (var item : command.bookingItems()) {
                SettlementLine line = new SettlementLine(
                        periodId,
                        item.bookingId(),
                        item.bookingCode(),
                        item.checkInDate(),
                        item.checkOutDate(),
                        item.grossRevenue(),
                        item.commissionRate(),
                        item.cleaningFee(),
                        item.currency()
                );
                lineRepository.save(line);

                totalGross = totalGross.add(line.getGrossRevenue());
                totalCommission = totalCommission.add(line.getCommissionAmount());
                totalCleaning = totalCleaning.add(line.getCleaningFee());
            }
        }

        List<SettlementAdjustment> adjustments = adjustmentRepository.findBySettlementPeriodId(periodId);
        BigDecimal totalAdjustments = adjustments.stream()
                .map(SettlementAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        period.updateTotals(totalGross, totalCommission, totalCleaning, totalAdjustments);
        periodRepository.save(period);

        return getSettlementPeriod(periodId);
    }

    @Transactional
    public SettlementPeriodResponse addAdjustment(UUID periodId, AddAdjustmentCommand command) {
        SettlementPeriod period = findPeriodOrThrow(periodId);

        if (period.getStatus() == SettlementStatus.APPROVED || period.getStatus() == SettlementStatus.PAID) {
            throw new IllegalStateException("Cannot add adjustments to an APPROVED or PAID settlement period");
        }

        BigDecimal rawAmount = command.amount().abs();
        BigDecimal signedAmount;
        if (command.adjustmentType() == AdjustmentType.PENALTY
                || command.adjustmentType() == AdjustmentType.DAMAGE_FEE
                || command.adjustmentType() == AdjustmentType.MANUAL_DEBIT) {
            signedAmount = rawAmount.negate();
        } else {
            signedAmount = rawAmount;
        }

        SettlementAdjustment adjustment = new SettlementAdjustment(
                periodId,
                command.adjustmentType(),
                signedAmount,
                command.currency(),
                command.reason(),
                command.createdByAdminUserId()
        );
        adjustmentRepository.save(adjustment);

        List<SettlementAdjustment> adjustments = adjustmentRepository.findBySettlementPeriodId(periodId);
        BigDecimal totalAdjustments = adjustments.stream()
                .map(SettlementAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        period.updateTotals(
                period.getTotalGrossRevenue(),
                period.getTotalCommission(),
                period.getTotalCleaningFees(),
                totalAdjustments
        );
        periodRepository.save(period);

        return getSettlementPeriod(periodId);
    }

    @Transactional
    public SettlementPeriodResponse submitForApproval(UUID periodId) {
        SettlementPeriod period = findPeriodOrThrow(periodId);
        period.submitForApproval();
        periodRepository.save(period);
        return getSettlementPeriod(periodId);
    }

    @Transactional
    public SettlementPeriodResponse approve(UUID periodId, MakerCheckerApprovalCommand command) {
        SettlementPeriod period = findPeriodOrThrow(periodId);
        period.approve(command.approverAdminUserId());
        periodRepository.save(period);
        return getSettlementPeriod(periodId);
    }

    @Transactional
    public SettlementPeriodResponse reject(UUID periodId, MakerCheckerApprovalCommand command, String reason) {
        SettlementPeriod period = findPeriodOrThrow(periodId);
        period.reject(command.approverAdminUserId(), reason);
        periodRepository.save(period);
        return getSettlementPeriod(periodId);
    }

    @Transactional
    public SettlementPeriodResponse reopen(UUID periodId, UUID adminUserId, String reason) {
        SettlementPeriod period = findPeriodOrThrow(periodId);
        period.reopen(adminUserId, reason);
        periodRepository.save(period);
        return getSettlementPeriod(periodId);
    }

    @Transactional(readOnly = true)
    public SettlementPeriodResponse getSettlementPeriod(UUID periodId) {
        SettlementPeriod period = findPeriodOrThrow(periodId);
        List<SettlementLineResponse> lines = lineRepository.findBySettlementPeriodId(periodId).stream()
                .map(SettlementLineResponse::from)
                .toList();
        List<SettlementAdjustmentResponse> adjustments = adjustmentRepository.findBySettlementPeriodId(periodId).stream()
                .map(SettlementAdjustmentResponse::from)
                .toList();
        return SettlementPeriodResponse.from(period, lines, adjustments);
    }

    @Transactional
    public PayableResponse createPayable(UUID periodId, UUID operatorAdminId) {
        SettlementPeriod period = findPeriodOrThrow(periodId);

        if (period.getStatus() != SettlementStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED settlement period can create payable. Current status: " + period.getStatus());
        }
        if (period.getPayableId() != null) {
            throw new IllegalStateException("Payable already created for settlement period: " + periodId);
        }

        String yearMonth = period.getStartDate().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String payableCode = "PAYABLE-" + yearMonth + "-" + suffix;
        String sourceKey = "SETTLEMENT:" + period.getId();

        UUID propertyId = period.getPropertyId();
        UUID contractId = (propertyId == null) ? period.getId() : null;

        Payable payable = new Payable(
                payableCode,
                sourceKey,
                PayeeType.HOST,
                period.getHostId(),
                propertyId,
                contractId,
                "HOST_SETTLEMENT",
                "Payout for settlement period " + period.getPeriodCode(),
                period.getStartDate(),
                period.getEndDate(),
                period.getDueDate(),
                period.getTotalNetPayout(),
                period.getCurrency()
        );

        Payable savedPayable = payableRepository.save(payable);
        period.markPayableCreated(savedPayable.getId());
        periodRepository.save(period);

        return PayableResponse.from(savedPayable);
    }

    @Transactional
    public PayableResponse recordPayout(UUID periodId, RecordPayoutCommand command) {
        SettlementPeriod period = findPeriodOrThrow(periodId);

        if (period.getPayableId() == null) {
            throw new IllegalStateException("No payable created yet for settlement period: " + periodId);
        }

        Payable payable = payableRepository.findById(period.getPayableId())
                .orElseThrow(() -> new NoSuchElementException("Payable not found: " + period.getPayableId()));

        payable.recordPayment(command.amount());

        if (payable.getStatus() == PayableStatus.SETTLED) {
            period.markPaid();
            periodRepository.save(period);
        }

        payableRepository.save(payable);
        return PayableResponse.from(payable);
    }

    @Transactional(readOnly = true)
    public FinanceDashboardSummaryResponse getDashboardSummary() {
        List<SettlementPeriod> periods = periodRepository.findAll();
        List<Payable> payables = payableRepository.findAll();

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalCleaning = BigDecimal.ZERO;
        BigDecimal totalAdjustments = BigDecimal.ZERO;
        BigDecimal totalNetPayout = BigDecimal.ZERO;

        long approvedCount = 0;
        long paidCount = 0;

        for (SettlementPeriod p : periods) {
            if (p.getStatus() != SettlementStatus.CANCELLED && p.getStatus() != SettlementStatus.REJECTED) {
                totalGross = totalGross.add(p.getTotalGrossRevenue());
                totalCommission = totalCommission.add(p.getTotalCommission());
                totalCleaning = totalCleaning.add(p.getTotalCleaningFees());
                totalAdjustments = totalAdjustments.add(p.getTotalAdjustments());
                totalNetPayout = totalNetPayout.add(p.getTotalNetPayout());
            }
            if (p.getStatus() == SettlementStatus.APPROVED || p.getStatus() == SettlementStatus.PAYABLE_CREATED || p.getStatus() == SettlementStatus.PAID) {
                approvedCount++;
            }
            if (p.getStatus() == SettlementStatus.PAID) {
                paidCount++;
            }
        }

        BigDecimal totalPayableOutstanding = payables.stream()
                .filter(p -> p.getStatus() == PayableStatus.OPEN || p.getStatus() == PayableStatus.PARTIALLY_PAID || p.getStatus() == PayableStatus.OVERDUE)
                .map(Payable::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPayablePaid = payables.stream()
                .map(Payable::getAmountPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new FinanceDashboardSummaryResponse(
                totalGross,
                totalCommission,
                totalCleaning,
                totalAdjustments,
                totalNetPayout,
                totalPayableOutstanding,
                totalPayablePaid,
                periods.size(),
                approvedCount,
                paidCount
        );
    }

    @Transactional(readOnly = true)
    public List<SettlementDashboardExportRow> exportDashboard(LocalDate fromDate, LocalDate toDate) {
        List<SettlementPeriod> periods = periodRepository.findAll();

        return periods.stream()
                .filter(p -> {
                    if (fromDate != null && p.getEndDate().isBefore(fromDate)) {
                        return false;
                    }
                    if (toDate != null && p.getStartDate().isAfter(toDate)) {
                        return false;
                    }
                    return true;
                })
                .map(p -> {
                    String payableCode = null;
                    String payableStatus = null;
                    BigDecimal payableAmountDue = null;
                    BigDecimal payableAmountPaid = null;

                    if (p.getPayableId() != null) {
                        Optional<Payable> payableOpt = payableRepository.findById(p.getPayableId());
                        if (payableOpt.isPresent()) {
                            Payable payable = payableOpt.get();
                            payableCode = payable.getPayableCode();
                            payableStatus = payable.getStatus().name();
                            payableAmountDue = payable.getAmountDue();
                            payableAmountPaid = payable.getAmountPaid();
                        }
                    }

                    return new SettlementDashboardExportRow(
                            p.getPeriodCode(),
                            p.getHostId(),
                            p.getPropertyId(),
                            p.getStartDate(),
                            p.getEndDate(),
                            p.getDueDate(),
                            p.getTotalGrossRevenue(),
                            p.getTotalCommission(),
                            p.getTotalCleaningFees(),
                            p.getTotalAdjustments(),
                            p.getTotalNetPayout(),
                            p.getCurrency(),
                            p.getStatus().name(),
                            payableCode,
                            payableStatus,
                            payableAmountDue,
                            payableAmountPaid,
                            p.getApprovedAt(),
                            p.getApprovedByAdminUserId()
                    );
                })
                .toList();
    }

    private SettlementPeriod findPeriodOrThrow(UUID periodId) {
        return periodRepository.findById(periodId)
                .orElseThrow(() -> new NoSuchElementException("Settlement period not found: " + periodId));
    }
}

