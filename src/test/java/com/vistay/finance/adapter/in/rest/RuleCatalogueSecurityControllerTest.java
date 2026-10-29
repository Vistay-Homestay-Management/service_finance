package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.RuleCatalogueService;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RuleCatalogueSecurityControllerTest {

    @Mock
    private RuleCatalogueService ruleCatalogueService;

    @InjectMocks
    private RuleCatalogueController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                        return (auth != null && auth.getPrincipal() instanceof Jwt jwt) ? jwt : null;
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RequestPostProcessor withJwt(String subject, String permission) {
        return request -> {
            var jwtBuilder = Jwt.withTokenValue("mock-jwt-token")
                    .header("alg", "none")
                    .subject(subject)
                    .claim("aud", List.of("vistay-services"))
                    .claim("account_type", "ADMIN")
                    .claim("grants", List.of(Map.of("p", permission, "s", "ALL")));

            Jwt jwt = jwtBuilder.build();
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_" + permission));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, authorities));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    @DisplayName("Request without JWT token is rejected with 401 Unauthorized")
    void unauthenticatedRequest_Returns401() throws Exception {
        mockMvc.perform(post("/api/v1/finance/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleCode": "RULE-TEST-01",
                                  "ruleName": "Test Rule",
                                  "ruleType": "PRICING",
                                  "parametersJson": "{}",
                                  "effectiveFrom": "2026-06-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Request missing required grant is rejected with 403 Forbidden")
    void missingGrant_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withJwt("ADMIN:102", "some:unrelated:permission"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleCode": "RULE-TEST-01",
                                  "ruleName": "Test Rule",
                                  "ruleType": "PRICING",
                                  "parametersJson": "{}",
                                  "effectiveFrom": "2026-06-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Create rule extracts actor identity from JWT subject and returns 201 Created")
    void createRule_ExtractsActorFromJwt() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.DRAFT);
        when(ruleCatalogueService.createRule(any(CreateRuleCatalogueCommand.class), eq("ADMIN:101")))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withJwt("ADMIN:101", "finance:rules:create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleCode": "RULE-PRICING-V1",
                                  "ruleName": "Quy tắc giá v1",
                                  "ruleType": "PRICING",
                                  "parametersJson": "{}",
                                  "effectiveFrom": "2026-06-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByAdminUserId").value("ADMIN:101"));

        verify(ruleCatalogueService).createRule(any(CreateRuleCatalogueCommand.class), eq("ADMIN:101"));
    }

    @Test
    @DisplayName("Approve grant alone cannot create rule (Decoupled permissions)")
    void approveGrant_CannotCreate_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withJwt("ADMIN:202", "finance:rules:approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleCode": "RULE-PRICING-V1",
                                  "ruleName": "Quy tắc giá v1",
                                  "ruleType": "PRICING",
                                  "parametersJson": "{}",
                                  "effectiveFrom": "2026-06-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Submit for approval requires finance:rules:submit grant")
    void submitRule_RequiresSubmitGrant() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.PENDING_APPROVAL);
        when(ruleCatalogueService.submitForApproval(ruleId)).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/submit", ruleId)
                        .with(withJwt("ADMIN:101", "finance:rules:submit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        verify(ruleCatalogueService).submitForApproval(ruleId);
    }

    @Test
    @DisplayName("Approve rule passes authenticated checker actor identity from JWT")
    void approveRule_PassesCheckerActor() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.ACTIVE);
        when(ruleCatalogueService.approveRule(ruleId, "ADMIN:202")).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/approve", ruleId)
                        .with(withJwt("ADMIN:202", "finance:rules:approve")))
                .andExpect(status().isOk());

        verify(ruleCatalogueService).approveRule(ruleId, "ADMIN:202");
    }

    @Test
    @DisplayName("Maker-Checker violation in service returns 409 Conflict")
    void approveRule_MakerCheckerViolation_Returns409() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(ruleCatalogueService.approveRule(ruleId, "ADMIN:101"))
                .thenThrow(new IllegalStateException("Maker-Checker violation: Creator cannot approve their own rule catalogue"));

        mockMvc.perform(post("/api/v1/finance/rules/{id}/approve", ruleId)
                        .with(withJwt("ADMIN:101", "finance:rules:approve")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Reject rule requires finance:rules:reject grant and passes reviewer actor identity")
    void rejectRule_PassesReviewerActor() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.REJECTED);
        when(ruleCatalogueService.rejectRule(ruleId, "ADMIN:202", "Ty le chiet khau chua phu hop"))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/reject", ruleId)
                        .with(withJwt("ADMIN:202", "finance:rules:reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Ty le chiet khau chua phu hop"
                                }
                                """))
                .andExpect(status().isOk());

        verify(ruleCatalogueService).rejectRule(ruleId, "ADMIN:202", "Ty le chiet khau chua phu hop");
    }

    @Test
    @DisplayName("Archive rule requires finance:rules:archive grant")
    void archiveRule_RequiresArchiveGrant() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.ARCHIVED);
        when(ruleCatalogueService.archiveRule(ruleId)).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/archive", ruleId)
                        .with(withJwt("ADMIN:202", "finance:rules:archive")))
                .andExpect(status().isOk());

        verify(ruleCatalogueService).archiveRule(ruleId);
    }

    @Test
    @DisplayName("Get rule by ID requires finance:rules:read grant")
    void getRuleById_RequiresReadGrant() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.ACTIVE);
        when(ruleCatalogueService.getRuleById(ruleId)).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/finance/rules/{id}", ruleId)
                        .with(withJwt("ADMIN:101", "finance:rules:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ruleId.toString()));

        verify(ruleCatalogueService).getRuleById(ruleId);
    }

    private RuleCatalogueResponse createMockResponse(UUID id, String creator, RuleStatus status) {
        return new RuleCatalogueResponse(
                id,
                "RULE-01",
                "Rule Test",
                RuleType.PRICING,
                1,
                0,
                "Description",
                "{}",
                Instant.now(),
                null,
                status,
                creator,
                Instant.now(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }
}

