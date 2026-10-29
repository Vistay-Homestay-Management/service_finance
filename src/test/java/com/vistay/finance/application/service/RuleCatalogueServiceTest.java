package com.vistay.finance.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.validation.RuleParameterValidator;
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
import java.util.NoSuchElementException;
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

    private RuleParameterValidator parameterValidator;
    private RuleCatalogueService service;

    private final String makerAdminId = "ADMIN:101";
    private final String checkerAdminId = "ADMIN:102";

    @BeforeEach
    void setUp() {
        parameterValidator = new RuleParameterValidator(new ObjectMapper());
        service = new RuleCatalogueService(ruleRepository, parameterValidator);
    }

    @Test
    @DisplayName("SCRUM-317: Tạo quy tắc giá/hủy/cọc/đối soát thành công ở trạng thái DRAFT version 1")
    void createRule_Success() {
        CreateRuleCatalogueCommand cmd = new CreateRuleCatalogueCommand(
                "RULE-PRICING-SUMMER26",
                "Quy tắc phụ thu mùa cao điểm 2026",
                RuleType.PRICING,
                "Tăng giá 20% các ngày cuối tuần",
                "{\"weekendMultiplier\": 1.20, \"currency\": \"VND\"}",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(90, ChronoUnit.DAYS)
        );

        when(ruleRepository.existsByRuleCodeAndRuleVersion("RULE-PRICING-SUMMER26", 1)).thenReturn(false);
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
    @DisplayName("SCRUM-317: Trùng mã quy tắc rule_code ở version 1 phải bị từ chối")
    void createRule_DuplicateCodeVersion1_ThrowsException() {
        CreateRuleCatalogueCommand cmd = new CreateRuleCatalogueCommand(
                "RULE-PRICING-SUMMER26",
                "Tên quy tắc",
                RuleType.PRICING,
                "Mô tả",
                "{}",
                Instant.now(),
                null
        );

        when(ruleRepository.existsByRuleCodeAndRuleVersion("RULE-PRICING-SUMMER26", 1)).thenReturn(true);

        assertThatThrownBy(() -> service.createRule(cmd, makerAdminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists at version 1");
    }

    @Test
    @DisplayName("SCRUM-317: Tạo phiên bản kế tiếp (Version 2) thành công kế thừa logical code và ruleType")
    void createNewVersion_Success() {
        RuleCatalogue v1 = new RuleCatalogue(
                "RULE-PRICING-SEASONAL",
                "Quy tắc mùa cao điểm v1",
                RuleType.PRICING,
                1,
                "Tăng 10%",
                "{\"weekendMultiplier\": 1.10}",
                Instant.now(),
                Instant.now().plus(60, ChronoUnit.DAYS),
                makerAdminId
        );

        CreateRuleVersionCommand cmd = new CreateRuleVersionCommand(
                "Quy tắc mùa cao điểm v2",
                "Tăng 25% năm mới",
                "{\"weekendMultiplier\": 1.25}",
                Instant.now().plus(60, ChronoUnit.DAYS),
                Instant.now().plus(120, ChronoUnit.DAYS)
        );

        when(ruleRepository.findTopByRuleCodeOrderByRuleVersionDesc("RULE-PRICING-SEASONAL"))
                .thenReturn(Optional.of(v1));
        when(ruleRepository.save(any(RuleCatalogue.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleCatalogueResponse response = service.createNewVersion("RULE-PRICING-SEASONAL", cmd, "ADMIN:103");

        assertThat(response).isNotNull();
        assertThat(response.ruleCode()).isEqualTo("RULE-PRICING-SEASONAL");
        assertThat(response.ruleVersion()).isEqualTo(2);
        assertThat(response.ruleType()).isEqualTo(RuleType.PRICING);
        assertThat(response.status()).isEqualTo(RuleStatus.DRAFT);
        assertThat(response.createdByAdminUserId()).isEqualTo("ADMIN:103");
    }

    @Test
    @DisplayName("SCRUM-317: Tham số JSON không hợp lệ (không phải object, âm, hoặc sai kiểu) bị chặn")
    void createRule_InvalidJsonParameters_ThrowsException() {
        CreateRuleCatalogueCommand nonObjectCmd = new CreateRuleCatalogueCommand(
                "RULE-BAD-JSON",
                "Bad JSON Rule",
                RuleType.PRICING,
                "Array",
                "[1, 2, 3]",
                Instant.now(),
                null
        );

        assertThatThrownBy(() -> service.createRule(nonObjectCmd, makerAdminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON object");

        CreateRuleCatalogueCommand negativeMultiplierCmd = new CreateRuleCatalogueCommand(
                "RULE-BAD-MULT",
                "Negative Mult",
                RuleType.PRICING,
                "Negative",
                "{\"weekendMultiplier\": -1.5}",
                Instant.now(),
                null
        );

        assertThatThrownBy(() -> service.createRule(negativeMultiplierCmd, makerAdminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weekendMultiplier");
    }

    @Test
    @DisplayName("SCRUM-317: Gửi yêu cầu phê duyệt chuyển trạng thái từ DRAFT sang PENDING_APPROVAL")
    void submitForApproval_Success() {
        RuleCatalogue rule = new RuleCatalogue(
                "RULE-CANCEL-STANDARD",
                "Chính sách hủy tiêu chuẩn",
                RuleType.CANCELLATION,
                "Hủy trước 24h hoàn 100%",
                "{\"freeCancelHours\": 24, \"penaltyNights\": 1, \"noShowCutoffHour\": 18}",
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
                "{\"depositRate\": 0.50, \"holdMinutes\": 15}",
                Instant.now(),
                null,
                makerAdminId
        );
        rule.submitForApproval();

        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.findActiveEffectiveRulesByCode(eq("RULE-DEPOSIT-V1"), any(Instant.class)))
                .thenReturn(List.of());
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
    @DisplayName("SCRUM-317: Phê duyệt quy tắc bị trùng khoảng hiệu lực với quy tắc ACTIVE khác phải ném lỗi Conflict")
    void approveRule_OverlappingActiveRule_ThrowsException() {
        Instant t0 = Instant.now();
        Instant t1 = t0.plus(30, ChronoUnit.DAYS);

        RuleCatalogue existingActive = new RuleCatalogue(
                "RULE-PRICING-SUMMER",
                "Bản 1",
                RuleType.PRICING,
                1,
                "v1",
                "{}",
                t0,
                t1,
                makerAdminId
        );
        existingActive.submitForApproval();
        existingActive.approve("ADMIN:CHECKER_01");

        RuleCatalogue candidate = new RuleCatalogue(
                "RULE-PRICING-SUMMER",
                "Bản 2 trùng",
                RuleType.PRICING,
                2,
                "v2",
                "{}",
                t0.plus(10, ChronoUnit.DAYS),
                t1.plus(10, ChronoUnit.DAYS),
                makerAdminId
        );
        candidate.submitForApproval();

        when(ruleRepository.findById(candidate.getId())).thenReturn(Optional.of(candidate));
        when(ruleRepository.findActiveEffectiveRulesByCode(eq("RULE-PRICING-SUMMER"), any(Instant.class)))
                .thenReturn(List.of(existingActive));

        assertThatThrownBy(() -> service.approveRule(candidate.getId(), checkerAdminId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Overlapping ACTIVE rule version");
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
    @DisplayName("SCRUM-317: Lấy quy tắc ACTIVE theo code trả về duy nhất bản ghi có hiệu lực")
    void getActiveEffectiveRule_ByCode_Success() {
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

        when(ruleRepository.findActiveEffectiveRulesByCode(eq("RULE-SETTLE-ACTIVE"), any(Instant.class)))
                .thenReturn(List.of(rule));

        Optional<RuleCatalogueResponse> result = service.getActiveEffectiveRule(RuleType.SETTLEMENT, "RULE-SETTLE-ACTIVE", now);

        assertThat(result).isPresent();
        assertThat(result.get().ruleCode()).isEqualTo("RULE-SETTLE-ACTIVE");
    }

    @Test
    @DisplayName("SCRUM-317: Lấy quy tắc ACTIVE theo type khi có nhiều code khác nhau ném lỗi Ambiguous nếu không truyền code")
    void getActiveEffectiveRule_MultipleCodes_ThrowsAmbiguousException() {
        Instant now = Instant.now();
        RuleCatalogue r1 = new RuleCatalogue("RULE-P1", "P1", RuleType.PRICING, "Desc 1", "{}", now.minus(5, ChronoUnit.DAYS), null, makerAdminId);
        RuleCatalogue r2 = new RuleCatalogue("RULE-P2", "P2", RuleType.PRICING, "Desc 2", "{}", now.minus(5, ChronoUnit.DAYS), null, makerAdminId);

        when(ruleRepository.findActiveEffectiveRulesByType(eq(RuleType.PRICING), any(Instant.class)))
                .thenReturn(List.of(r1, r2));

        assertThatThrownBy(() -> service.getActiveEffectiveRule(RuleType.PRICING, null, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous active rules");
    }
}
