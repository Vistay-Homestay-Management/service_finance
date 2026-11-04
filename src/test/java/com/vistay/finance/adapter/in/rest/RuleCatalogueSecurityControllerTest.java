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
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

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

    @Mock
    private FinanceRuleAuthorization authorization;

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

    private RequestPostProcessor withGrant(String subject, String permission, String scope) {
        doAnswer(invocation -> {
            String requiredPermission = invocation.getArgument(1);
            if (!permission.equalsIgnoreCase(requiredPermission) || !"ALL".equalsIgnoreCase(scope)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shared authorization grant");
            }
            return null;
        }).when(authorization).requireAll(any(), anyString());
        return request -> {
            var jwtBuilder = Jwt.withTokenValue("mock-jwt-token")
                    .header("alg", "none")
                    .subject(subject)
                    .claim("aud", List.of("vistay-backoffice-api"))
                    .claim("account_type", "ADMIN")
                    .claim("grants", List.of(Map.of("p", permission, "s", scope)));

            Jwt jwt = jwtBuilder.build();
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_" + permission + "_" + scope));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, authorities));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private RequestPostProcessor withMultiGrants(String subject, List<Map<String, String>> grants) {
        doAnswer(invocation -> {
            String requiredPermission = invocation.getArgument(1);
            boolean allowed = grants.stream().anyMatch(grant -> {
                String permission = grant.getOrDefault("p", grant.get("permission"));
                String scope = grant.getOrDefault("s", grant.get("scope"));
                return requiredPermission.equalsIgnoreCase(permission) && "ALL".equalsIgnoreCase(scope);
            });
            if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shared authorization grant");
            }
            return null;
        }).when(authorization).requireAll(any(), anyString());
        return request -> {
            var jwtBuilder = Jwt.withTokenValue("mock-jwt-token")
                    .header("alg", "none")
                    .subject(subject)
                    .claim("aud", List.of("vistay-backoffice-api"))
                    .claim("account_type", "ADMIN")
                    .claim("grants", grants);

            Jwt jwt = jwtBuilder.build();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    @DisplayName("SCRUM-317: Request without JWT token is rejected with 401 Unauthorized")
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
    @DisplayName("SCRUM-317: Request missing required grant is rejected with 403 Forbidden")
    void missingGrant_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withGrant("ADMIN:102", "some:unrelated:permission", "ALL"))
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
    @DisplayName("SCRUM-317: Permission correct but data scope wrong (BRANCH instead of ALL) is rejected with 403 Forbidden")
    void matchingPermission_WrongScope_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withGrant("ADMIN:102", "finance:rules:create", "BRANCH"))
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
    @DisplayName("SCRUM-317: Cannot combine permission from Grant A with scope from Grant B to forge access (403 Forbidden)")
    void mixingGrants_CannotCombinePermissionFromGrantAWithScopeFromGrantB_Returns403() throws Exception {
        // Grant A has permission with BRANCH scope; Grant B has different permission with ALL scope
        var splitGrants = List.of(
                Map.of("p", "finance:rules:create", "s", "BRANCH"),
                Map.of("p", "other:service:action", "s", "ALL")
        );

        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withMultiGrants("ADMIN:102", splitGrants))
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
    @DisplayName("SCRUM-317: Create rule extracts actor from JWT sub and returns 201 Created with valid ALL scope grant")
    void createRule_ExtractsActorFromJwt() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.DRAFT, 1);
        when(ruleCatalogueService.createRule(any(CreateRuleCatalogueCommand.class), eq("ADMIN:101")))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules")
                        .with(withGrant("ADMIN:101", "finance:rules:create", "ALL"))
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
                .andExpect(jsonPath("$.createdByAdminUserId").value("ADMIN:101"))
                .andExpect(jsonPath("$.ruleVersion").value(1));

        verify(ruleCatalogueService).createRule(any(CreateRuleCatalogueCommand.class), eq("ADMIN:101"));
    }

    @Test
    @DisplayName("SCRUM-317: Create new version endpoint requires finance:rules:create grant with ALL scope")
    void createNewVersion_RequiresCreateGrantWithAllScope() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.DRAFT, 2);
        when(ruleCatalogueService.createNewVersion(eq("RULE-PRICING-V1"), any(CreateRuleVersionCommand.class), eq("ADMIN:101")))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{ruleCode}/versions", "RULE-PRICING-V1")
                        .with(withGrant("ADMIN:101", "finance:rules:create", "ALL"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleName": "Quy tắc giá v2",
                                  "parametersJson": "{}",
                                  "effectiveFrom": "2026-07-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(2));

        verify(ruleCatalogueService).createNewVersion(eq("RULE-PRICING-V1"), any(CreateRuleVersionCommand.class), eq("ADMIN:101"));
    }

    @Test
    @DisplayName("SCRUM-317: Submit for approval requires finance:rules:submit grant with ALL scope")
    void submitRule_RequiresSubmitGrant() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.PENDING_APPROVAL, 1);
        when(ruleCatalogueService.submitForApproval(ruleId)).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/submit", ruleId)
                        .with(withGrant("ADMIN:101", "finance:rules:submit", "ALL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        verify(ruleCatalogueService).submitForApproval(ruleId);
    }

    @Test
    @DisplayName("SCRUM-317: Approve rule requires finance:rules:approve grant with ALL scope and passes checker identity")
    void approveRule_PassesCheckerActor() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.ACTIVE, 1);
        when(ruleCatalogueService.approveRule(ruleId, "ADMIN:202")).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/approve", ruleId)
                        .with(withGrant("ADMIN:202", "finance:rules:approve", "ALL")))
                .andExpect(status().isOk());

        verify(ruleCatalogueService).approveRule(ruleId, "ADMIN:202");
    }

    @Test
    @DisplayName("SCRUM-317: Maker-Checker violation in service returns 409 Conflict")
    void approveRule_MakerCheckerViolation_Returns409() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(ruleCatalogueService.approveRule(ruleId, "ADMIN:101"))
                .thenThrow(new IllegalStateException("Maker-Checker violation: Creator cannot approve their own rule catalogue"));

        mockMvc.perform(post("/api/v1/finance/rules/{id}/approve", ruleId)
                        .with(withGrant("ADMIN:101", "finance:rules:approve", "ALL")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("SCRUM-317: Reject rule requires finance:rules:reject grant with ALL scope")
    void rejectRule_PassesReviewerActor() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.REJECTED, 1);
        when(ruleCatalogueService.rejectRule(ruleId, "ADMIN:202", "Ty le chiet khau chua phu hop"))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/reject", ruleId)
                        .with(withGrant("ADMIN:202", "finance:rules:reject", "ALL"))
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
    @DisplayName("SCRUM-317: Archive rule requires finance:rules:archive grant with ALL scope")
    void archiveRule_RequiresArchiveGrant() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.ARCHIVED, 1);
        when(ruleCatalogueService.archiveRule(ruleId)).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/finance/rules/{id}/archive", ruleId)
                        .with(withGrant("ADMIN:202", "finance:rules:archive", "ALL")))
                .andExpect(status().isOk());

        verify(ruleCatalogueService).archiveRule(ruleId);
    }

    @Test
    @DisplayName("SCRUM-317: Get rule by ID requires finance:rules:read grant with ALL scope; non-ALL scope is rejected with 403")
    void getRuleById_RejectsNonAllScopeWith403() throws Exception {
        UUID ruleId = UUID.randomUUID();

        // BRANCH scope is rejected because rule catalogue is a global resource
        mockMvc.perform(get("/api/v1/finance/rules/{id}", ruleId)
                        .with(withGrant("ADMIN:101", "finance:rules:read", "BRANCH")))
                .andExpect(status().isForbidden());

        // OWN scope is also rejected
        mockMvc.perform(get("/api/v1/finance/rules/{id}", ruleId)
                        .with(withGrant("ADMIN:101", "finance:rules:read", "OWN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SCRUM-317: Get rule by ID requires finance:rules:read grant with ALL scope")
    void getRuleById_RequiresReadGrant() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogueResponse mockResponse = createMockResponse(ruleId, "ADMIN:101", RuleStatus.ACTIVE, 1);
        when(ruleCatalogueService.getRuleById(ruleId)).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/finance/rules/{id}", ruleId)
                        .with(withGrant("ADMIN:101", "finance:rules:read", "ALL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ruleId.toString()));

        verify(ruleCatalogueService).getRuleById(ruleId);
    }

    private RuleCatalogueResponse createMockResponse(UUID id, String creator, RuleStatus status, int version) {
        return new RuleCatalogueResponse(
                id,
                "RULE-01",
                "Rule Test",
                RuleType.PRICING,
                version,
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
