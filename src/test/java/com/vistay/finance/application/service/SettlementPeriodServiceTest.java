package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.PayableRepository;
import com.vistay.finance.domain.repository.SettlementAdjustmentRepository;
import com.vistay.finance.domain.repository.SettlementLineRepository;
import com.vistay.finance.domain.repository.SettlementPeriodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementPeriodServiceTest {

    @Mock
    private SettlementPeriodRepository periodRepository;

    @Mock
    private SettlementLineRepository lineRepository;

    @Mock
    private SettlementAdjustmentRepository adjustmentRepository;

    @Mock
    private PayableRepository payableRepository;

    private SettlementPeriodService service;

    private final UUID hostId = UUID.randomUUID();
    private final UUID creatorAdminId = UUID.randomUUID();
    private final UUID reviewerAdminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SettlementPeriodService(periodRepository, lineRepository, adjustmentRepository, payableRepository);
    }

    @Test
    @DisplayName("SCRUM-386: Mở kỳ đối soát thành công với mã SETTLE-yyyyMM-XXXX")
    void openSettlementPeriod_Success() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 15);
        LocalDate due = LocalDate.of(2026, 10, 20);

        OpenSettlementPeriodCommand command = new OpenSettlementPeriodCommand(
                hostId, null, start, end, due, "VND", creatorAdminId, "Kỳ đối soát nửa đầu tháng 10"
        );

        when(periodRepository.save(any(SettlementPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SettlementPeriodResponse response = service.openSettlementPeriod(command);

        assertThat(response).isNotNull();
        assertThat(response.periodCode()).startsWith("SETTLE-202610-");
        assertThat(response.status()).isEqualTo(SettlementStatus.OPEN);
        assertThat(response.hostId()).isEqualTo(hostId);
        assertThat(response.startDate()).isEqualTo(start);
        assertThat(response.endDate()).isEqualTo(end);
        assertThat(response.dueDate()).isEqualTo(due);
        verify(periodRepository).save(any(SettlementPeriod.class));
    }

    @Test
    @DisplayName("SCRUM-386: Báo lỗi khi ngày kết thúc trước ngày bắt đầu hoặc hạn thanh toán trước ngày kết thúc")
    void openSettlementPeriod_InvalidDates() {
        LocalDate start = LocalDate.of(2026, 10, 15);
        LocalDate end = LocalDate.of(2026, 10, 1);
        LocalDate due = LocalDate.of(2026, 10, 20);

        OpenSettlementPeriodCommand command = new OpenSettlementPeriodCommand(
                hostId, null, start, end, due, "VND", creatorAdminId, null
        );

        assertThatThrownBy(() -> service.openSettlementPeriod(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("End date cannot be before start date");
    }

    @Test
    @DisplayName("SCRUM-387: Tính toán chính xác doanh thu, hoa hồng sàn, phí dọn dẹp và Net Payout từng dòng")
    void calculateSettlement_Success() {
        UUID periodId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(periodRepository.save(any(SettlementPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adjustmentRepository.findBySettlementPeriodId(periodId)).thenReturn(List.of());

        var item1 = new CalculateSettlementCommand.SettlementBookingItemDto(
                UUID.randomUUID(), "BK-001", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5),
                new BigDecimal("2000000.0000"), new BigDecimal("0.1000"), new BigDecimal("100000.0000"), "VND"
        );
        var item2 = new CalculateSettlementCommand.SettlementBookingItemDto(
                UUID.randomUUID(), "BK-002", LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 9),
                new BigDecimal("3000000.0000"), new BigDecimal("0.1500"), new BigDecimal("150000.0000"), "VND"
        );

        List<SettlementLine> savedLines = new ArrayList<>();
        when(lineRepository.save(any(SettlementLine.class))).thenAnswer(invocation -> {
            SettlementLine l = invocation.getArgument(0);
            savedLines.add(l);
            return l;
        });
        when(lineRepository.findBySettlementPeriodId(periodId)).thenReturn(savedLines);

        SettlementPeriodResponse response = service.calculateSettlement(periodId, new CalculateSettlementCommand(List.of(item1, item2)));

        assertThat(response.status()).isEqualTo(SettlementStatus.CALCULATED);
        assertThat(response.totalGrossRevenue()).isEqualByComparingTo("5000000.0000");
        assertThat(response.totalCommission()).isEqualByComparingTo("650000.0000");
        assertThat(response.totalCleaningFees()).isEqualByComparingTo("250000.0000");
        // Net payout = 5,000,000 - 650,000 + 250,000 = 4,600,000
        assertThat(response.totalNetPayout()).isEqualByComparingTo("4600000.0000");

        verify(lineRepository).deleteBySettlementPeriodId(periodId);
        verify(lineRepository, times(2)).save(any(SettlementLine.class));
    }

    @Test
    @DisplayName("SCRUM-388: Thêm khoản phạt vi phạm (PENALTY) tự động khấu trừ vào Net Payout")
    void addAdjustment_PenaltyDeduction() {
        UUID periodId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );
        period.updateTotals(
                new BigDecimal("5000000.0000"),
                new BigDecimal("650000.0000"),
                new BigDecimal("250000.0000"),
                BigDecimal.ZERO
        );

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));

        SettlementAdjustment penalty = new SettlementAdjustment(
                periodId, AdjustmentType.PENALTY, new BigDecimal("-500000.0000"), "VND", "Hủy phòng sát giờ phạt 500k", creatorAdminId
        );
        when(adjustmentRepository.findBySettlementPeriodId(periodId)).thenReturn(List.of(penalty));
        when(lineRepository.findBySettlementPeriodId(periodId)).thenReturn(List.of());

        AddAdjustmentCommand command = new AddAdjustmentCommand(
                AdjustmentType.PENALTY, new BigDecimal("500000.0000"), "VND", "Hủy phòng sát giờ phạt 500k", creatorAdminId
        );

        SettlementPeriodResponse response = service.addAdjustment(periodId, command);

        assertThat(response.totalAdjustments()).isEqualByComparingTo("-500000.0000");
        // Net payout: 4,600,000 - 500,000 = 4,100,000
        assertThat(response.totalNetPayout()).isEqualByComparingTo("4100000.0000");
        verify(adjustmentRepository).save(any(SettlementAdjustment.class));
    }

    @Test
    @DisplayName("SCRUM-389: Maker-Checker chặn người tạo tự duyệt kỳ đối soát của chính mình")
    void makerChecker_SelfApprovalBlocked() {
        UUID periodId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );
        period.updateTotals(new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        period.submitForApproval();

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));

        MakerCheckerApprovalCommand selfCommand = new MakerCheckerApprovalCommand(creatorAdminId, "Tự duyệt");

        assertThatThrownBy(() -> service.approve(periodId, selfCommand))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maker-checker violation: Creator cannot approve own settlement period");
    }

    @Test
    @DisplayName("SCRUM-389: Quản lý khác duyệt thành công kỳ đối soát (Maker != Checker)")
    void makerChecker_OtherAdminApprovalSuccess() {
        UUID periodId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );
        period.updateTotals(new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        period.submitForApproval();

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(periodRepository.save(any(SettlementPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lineRepository.findBySettlementPeriodId(periodId)).thenReturn(List.of());
        when(adjustmentRepository.findBySettlementPeriodId(periodId)).thenReturn(List.of());

        MakerCheckerApprovalCommand reviewerCommand = new MakerCheckerApprovalCommand(reviewerAdminId, "Đồng ý đối soát");

        SettlementPeriodResponse response = service.approve(periodId, reviewerCommand);

        assertThat(response.status()).isEqualTo(SettlementStatus.APPROVED);
        assertThat(response.approvedByAdminUserId()).isEqualTo(reviewerAdminId);
        assertThat(response.approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("SCRUM-390: Tạo khoản phải trả (Payable) từ kỳ đối soát đã APPROVED")
    void createPayable_Success() {
        UUID periodId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );
        period.updateTotals(new BigDecimal("5000000"), new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO);
        period.submitForApproval();
        period.approve(reviewerAdminId); // APPROVED

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(payableRepository.save(any(Payable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(periodRepository.save(any(SettlementPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PayableResponse payableResponse = service.createPayable(periodId, reviewerAdminId);

        assertThat(payableResponse).isNotNull();
        assertThat(payableResponse.payableCode()).startsWith("PAYABLE-202610-");
        assertThat(payableResponse.sourceKey()).isEqualTo("SETTLEMENT:" + periodId);
        assertThat(payableResponse.payeeType()).isEqualTo(PayeeType.HOST);
        assertThat(payableResponse.payeeId()).isEqualTo(hostId);
        assertThat(payableResponse.amountDue()).isEqualByComparingTo("4500000.0000");
        assertThat(payableResponse.amountPaid()).isEqualByComparingTo("0.0000");
        assertThat(payableResponse.status()).isEqualTo(PayableStatus.OPEN);

        assertThat(period.getStatus()).isEqualTo(SettlementStatus.PAYABLE_CREATED);
        assertThat(period.getPayableId()).isNotNull();
        verify(payableRepository).save(any(Payable.class));
        verify(periodRepository).save(period);
    }

    @Test
    @DisplayName("SCRUM-390: Chặn tạo Payable khi kỳ đối soát chưa được duyệt (chưa APPROVED)")
    void createPayable_ThrowsIfNotApproved() {
        UUID periodId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.createPayable(periodId, reviewerAdminId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only APPROVED settlement period can create payable");
    }

    @Test
    @DisplayName("SCRUM-390: Ghi nhận chi trả (Payout) từng phần và tất toán (SETTLED / PAID)")
    void recordPayout_PartialAndFullSettlement() {
        UUID periodId = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );
        period.updateTotals(new BigDecimal("5000000"), new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO);
        period.submitForApproval();
        period.approve(reviewerAdminId);
        period.markPayableCreated(payableId);

        Payable payable = new Payable(
                payableId, "PAYABLE-202610-0001", "SETTLEMENT:" + periodId,
                PayeeType.HOST, hostId, null, periodId, "HOST_SETTLEMENT",
                "Payout", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), new BigDecimal("4500000.0000"), "VND"
        );

        when(periodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(payableRepository.findById(payableId)).thenReturn(Optional.of(payable));
        when(payableRepository.save(any(Payable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(periodRepository.save(any(SettlementPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Đợt 1: Trả một phần 2,000,000 VND
        RecordPayoutCommand partPayout = new RecordPayoutCommand(
                new BigDecimal("2000000.0000"), "BANK-REF-001", "Chi trả đợt 1", reviewerAdminId
        );
        PayableResponse partResp = service.recordPayout(periodId, partPayout);
        assertThat(partResp.status()).isEqualTo(PayableStatus.PARTIALLY_PAID);
        assertThat(partResp.amountPaid()).isEqualByComparingTo("2000000.0000");
        assertThat(partResp.remainingAmount()).isEqualByComparingTo("2500000.0000");
        assertThat(period.getStatus()).isEqualTo(SettlementStatus.PAYABLE_CREATED);

        // Đợt 2: Trả nốt 2,500,000 VND -> SETTLED
        RecordPayoutCommand fullPayout = new RecordPayoutCommand(
                new BigDecimal("2500000.0000"), "BANK-REF-002", "Tất toán", reviewerAdminId
        );
        PayableResponse fullResp = service.recordPayout(periodId, fullPayout);
        assertThat(fullResp.status()).isEqualTo(PayableStatus.SETTLED);
        assertThat(fullResp.amountPaid()).isEqualByComparingTo("4500000.0000");
        assertThat(fullResp.remainingAmount()).isEqualByComparingTo("0.0000");
        assertThat(period.getStatus()).isEqualTo(SettlementStatus.PAID);
    }

    @Test
    @DisplayName("SCRUM-390: Trích xuất tổng hợp Dashboard tài chính và dòng kiểm toán xuất báo cáo")
    void getDashboardSummary_And_Export_Success() {
        UUID periodId = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        SettlementPeriod period = new SettlementPeriod(
                periodId, "SETTLE-202610-0001", hostId, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), "VND", creatorAdminId, null
        );
        period.updateTotals(new BigDecimal("10000000"), new BigDecimal("1000000"), new BigDecimal("200000"), BigDecimal.ZERO);
        period.submitForApproval();
        period.approve(reviewerAdminId);
        period.markPayableCreated(payableId);

        Payable payable = new Payable(
                payableId, "PAYABLE-202610-0001", "SETTLEMENT:" + periodId,
                PayeeType.HOST, hostId, null, periodId, "HOST_SETTLEMENT",
                "Payout", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20), new BigDecimal("9200000.0000"), "VND"
        );
        payable.recordPayment(new BigDecimal("4000000.0000")); // partially paid 4M, remaining 5.2M

        when(periodRepository.findAll()).thenReturn(List.of(period));
        when(payableRepository.findAll()).thenReturn(List.of(payable));
        when(payableRepository.findById(payableId)).thenReturn(Optional.of(payable));

        // 1. Dashboard summary
        FinanceDashboardSummaryResponse summary = service.getDashboardSummary();
        assertThat(summary.totalGrossRevenue()).isEqualByComparingTo("10000000.0000");
        assertThat(summary.totalCommissionEarned()).isEqualByComparingTo("1000000.0000");
        assertThat(summary.totalCleaningFees()).isEqualByComparingTo("200000.0000");
        assertThat(summary.totalNetPayoutCalculated()).isEqualByComparingTo("9200000.0000");
        assertThat(summary.totalPayableOutstanding()).isEqualByComparingTo("5200000.0000");
        assertThat(summary.totalPayablePaid()).isEqualByComparingTo("4000000.0000");
        assertThat(summary.approvedSettlementCount()).isEqualTo(1);

        // 2. Export rows
        List<SettlementDashboardExportRow> rows = service.exportDashboard(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).periodCode()).isEqualTo("SETTLE-202610-0001");
        assertThat(rows.get(0).payableCode()).isEqualTo("PAYABLE-202610-0001");
        assertThat(rows.get(0).payableAmountPaid()).isEqualByComparingTo("4000000.0000");
    }
}

