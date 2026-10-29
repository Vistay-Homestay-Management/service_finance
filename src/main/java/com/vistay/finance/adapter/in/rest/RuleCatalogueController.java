package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.RuleCatalogueService;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/rules")
public class RuleCatalogueController {

    private final RuleCatalogueService ruleCatalogueService;

    public RuleCatalogueController(RuleCatalogueService ruleCatalogueService) {
        this.ruleCatalogueService = ruleCatalogueService;
    }

    @PostMapping
    public ResponseEntity<RuleCatalogueResponse> createRule(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_CREATE, FinanceRuleSecurity.SCOPE_ALL);
        String actorId = FinanceRuleSecurity.extractActorIdentity(jwt);
        RuleCatalogueResponse response = ruleCatalogueService.createRule(command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{ruleCode}/versions")
    public ResponseEntity<RuleCatalogueResponse> createNewVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String ruleCode,
            @Valid @RequestBody CreateRuleVersionCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_CREATE, FinanceRuleSecurity.SCOPE_ALL);
        String actorId = FinanceRuleSecurity.extractActorIdentity(jwt);
        RuleCatalogueResponse response = ruleCatalogueService.createNewVersion(ruleCode, command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<RuleCatalogueResponse> submitForApproval(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_SUBMIT, FinanceRuleSecurity.SCOPE_ALL);
        return ResponseEntity.ok(ruleCatalogueService.submitForApproval(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<RuleCatalogueResponse> approveRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_APPROVE, FinanceRuleSecurity.SCOPE_ALL);
        String approverId = FinanceRuleSecurity.extractActorIdentity(jwt);
        return ResponseEntity.ok(ruleCatalogueService.approveRule(id, approverId));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<RuleCatalogueResponse> rejectRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody RejectRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_REJECT, FinanceRuleSecurity.SCOPE_ALL);
        String reviewerId = FinanceRuleSecurity.extractActorIdentity(jwt);
        return ResponseEntity.ok(ruleCatalogueService.rejectRule(id, reviewerId, command.reason()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<RuleCatalogueResponse> archiveRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_ARCHIVE, FinanceRuleSecurity.SCOPE_ALL);
        return ResponseEntity.ok(ruleCatalogueService.archiveRule(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuleCatalogueResponse> getRuleById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        FinanceRuleSecurity.assertAuthenticatedAndHasReadGrant(jwt);
        return ResponseEntity.ok(ruleCatalogueService.getRuleById(id));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<List<RuleCatalogueResponse>> getRuleVersionsByCode(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String code) {
        FinanceRuleSecurity.assertAuthenticatedAndHasReadGrant(jwt);
        return ResponseEntity.ok(ruleCatalogueService.getRuleVersionsByCode(code));
    }

    @GetMapping("/code/{code}/version/{version}")
    public ResponseEntity<RuleCatalogueResponse> getRuleByCodeAndVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String code,
            @PathVariable int version) {
        FinanceRuleSecurity.assertAuthenticatedAndHasReadGrant(jwt);
        return ResponseEntity.ok(ruleCatalogueService.getRuleByCodeAndVersion(code, version));
    }

    @GetMapping
    public ResponseEntity<List<RuleCatalogueResponse>> getRules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) RuleType type,
            @RequestParam(required = false) RuleStatus status) {
        FinanceRuleSecurity.assertAuthenticatedAndHasReadGrant(jwt);
        return ResponseEntity.ok(ruleCatalogueService.getRules(type, status));
    }

    @GetMapping("/active")
    public ResponseEntity<RuleCatalogueResponse> getActiveEffectiveRule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam RuleType type,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) Instant at) {
        FinanceRuleSecurity.assertAuthenticatedAndHasReadGrant(jwt);
        return ruleCatalogueService.getActiveEffectiveRule(type, code, at)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        Throwable rootCause = ex.getMostSpecificCause();
        String rootMsg = rootCause != null ? rootCause.getMessage() : ex.getMessage();

        if (rootMsg != null) {
            if (rootMsg.contains("uq_rule_catalogue_code_version")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Conflict", "message", "Concurrent version creation conflict: this rule version already exists. Please retry."));
            }
            if (rootMsg.contains("ex_rule_catalogue_active_overlap")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Conflict", "message", "Overlapping active rule conflict: another active rule covers this effective time window."));
            }
            if (rootMsg.contains("ck_rule_maker_checker")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Conflict", "message", "Maker-Checker violation: rule creator cannot approve the rule."));
            }
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Conflict", "message", "Data integrity conflict: " + rootMsg));
    }
}
