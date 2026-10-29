package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.RuleCatalogue;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleCatalogueServiceTest {

    @Mock
    private RuleCatalogueRepository ruleRepository;

    private RuleCatalogueService service;

    private final String makerAdminId = "ADMIN:101";
    private final String checkerAdminId = "ADMIN:102";

    @BeforeEach
    void setUp() {
        service = new RuleCatalogueService(ruleRepository);
    }

    @Test
    @DisplayName("SCRUM-317: Tạo quy tắc giá/hủy/cọc/đối soát thành công ở trạng thái DRAFT")
    void createRule_Success() {
        CreateRuleCatalogueCommand cmd = new CreateRuleCatalogueCommand(
                "RULE-PRICING-SUMMER26",
                "Quy tắc phụ thu mùa cao điểm 2026",
                RuleType.PRICING,
                "Tăng giá 20% các ngày cuối tuần tháng 6-8",
                "{\"weekendMultiplier\": 1.20}",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(90, ChronoUnit.DAYS)
        );

        when(ruleRepository.existsByRuleCode("RULE-PRICING-SUMMER26")).thenReturn(false);
        when(ruleRepository.save(any(RuleCatalogue.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleCatalogueResponse response = service.createRule(cmd, makerAdminId);

        assertThat(response).isNotNull();
        assertThat(response.ruleCode()).isEqualTo("RULE-PRICING-SUMMER26");
        assertThat(response.ruleType()).isEqualTo(RuleType.PRICING);
        assertThat(response.status()).isEqualTo(RuleStatus.DRAFT);
        assertThat(response.createdByAdminUserId()).isEqualTo(makerAdminId);
        assertThat(response.ruleVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("SCRUM-317: Trùng mã quy tắc rule_code phải bị từ chối")
    void createRule_DuplicateCode_ThrowsException() {
        CreateRuleCatalogueCommand cmd = new CreateRuleCatalogueCommand(
                "RULE-PRICING-SUMMER26",
                "Tên quy tắc",
                RuleType.PRICING,
                "Mô tả",
                "{}",
                Instant.now(),
                null
        );

        when(ruleRepository.existsByRuleCode("RULE-PRICING-SUMMER26")).thenReturn(true);

        assertThatThrownBy(() -> service.createRule(cmd, makerAdminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("SCRUM-317: Gửi yêu cầu phê duyệt chuyển trạng thái từ DRAFT sang PENDING_APPROVAL")
    void submitForApproval_Success() {
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-CANCEL-STANDARD",
                "Chính sách hủy tiêu chuẩn",
                RuleType.CANCELLATION,
                "Hủy trước 24h hoàn 100%",
                "{\"freeCancelHours\": 24}",
                Instant.now(),
                null,
                makerAdminId
        );

        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(RuleCatalogue.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleCatalogueResponse response = service.submitForApproval(rule.getId());

        assertThat(response.status()).isEqualTo(RuleStatus.PENDING_APPROVAL);
        assertThat(response.submittedAt()).isNotNull();
    }

    @Test
    @DisplayName("SCRUM-317: Người kiểm duyệt độc lập (Checker) duyệt thành công sang ACTIVE")
    void approveRule_Success() {
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-DEPOSIT-V1",
                "Chính sách đặt cọc 50%",
                RuleType.DEPOSIT,
                "Đặt cọc 50% tiền phòng",
                "{\"depositRate\": 0.50}",
                Instant.now(),
                null,
                makerAdminId
        );
        rule.submitForApproval();

        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(RuleCatalogue.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleCatalogueResponse response = service.approveRule(rule.getId(), checkerAdminId);

        assertThat(response.status()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(response.approvedByAdminUserId()).isEqualTo(checkerAdminId);
        assertThat(response.approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("SCRUM-317: Vi phạm Maker-Checker (Người tạo tự duyệt) phải bị ném lỗi Conflict")
    void approveRule_MakerCheckerViolation_ThrowsException() {
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-SETTLE-V1",
                "Quy tắc chiết khấu sàn 15%",
                RuleType.SETTLEMENT,
                "Chiết khấu 15%",
                "{\"commissionRate\": 0.15}",
                Instant.now(),
                null,
                makerAdminId
        );
        rule.submitForApproval();

        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.approveRule(rule.getId(), makerAdminId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maker-Checker violation");
    }

    @Test
    @DisplayName("SCRUM-317: Checker từ chối quy tắc với lý do cụ thể chuyển sang REJECTED")
    void rejectRule_Success() {
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-REFUND-V1",
                "Chính sách hoàn tiền",
                RuleType.REFUND,
                "Hoàn 80%",
                "{\"refundRate\": 0.80}",
                Instant.now(),
                null,
                makerAdminId
        );
        rule.submitForApproval();

        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(RuleCatalogue.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleCatalogueResponse response = service.rejectRule(
                rule.getId(),
                checkerAdminId,
                "Tỷ lệ hoàn quá cao theo quy định mới"
        );

        assertThat(response.status()).isEqualTo(RuleStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("Tỷ lệ hoàn quá cao theo quy định mới");
        assertThat(response.approvedByAdminUserId()).isEqualTo(checkerAdminId);
    }

    @Test
    @DisplayName("SCRUM-317: Lưu trữ (Archive) quy tắc đang ACTIVE")
    void archiveRule_Success() {
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-OLD-V1",
                "Quy tắc cũ",
                RuleType.PRICING,
                "Mô tả",
                "{}",
                Instant.now(),
                null,
                makerAdminId
        );
        rule.submitForApproval();
        rule.approve(checkerAdminId);

        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(RuleCatalogue.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleCatalogueResponse response = service.archiveRule(rule.getId());

        assertThat(response.status()).isEqualTo(RuleStatus.ARCHIVED);
    }

    @Test
    @DisplayName("SCRUM-317: Lấy quy tắc ACTIVE có hiệu lực tại thời điểm chỉ định")
    void getActiveEffectiveRule_Success() {
        Instant now = Instant.now();
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-SETTLE-ACTIVE",
                "Quy tắc đối soát hiện hành",
                RuleType.SETTLEMENT,
                "Hoa hồng 12%",
                "{\"commissionRate\": 0.12}",
                now.minus(10, ChronoUnit.DAYS),
                now.plus(30, ChronoUnit.DAYS),
                makerAdminId
        );
        rule.submitForApproval();
        rule.approve(checkerAdminId);

        when(ruleRepository.findActiveEffectiveRules(eq(RuleType.SETTLEMENT), any(Instant.class)))
                .thenReturn(List.of(rule));

        Optional<RuleCatalogueResponse> result = service.getActiveEffectiveRule(RuleType.SETTLEMENT, now);

        assertThat(result).isPresent();
        assertThat(result.get().ruleCode()).isEqualTo("RULE-SETTLE-ACTIVE");
    }
}

