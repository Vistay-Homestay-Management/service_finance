package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.RuleCatalogueService;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_CREATE);
        String actorId = FinanceRuleSecurity.extractActorIdentity(jwt);
        RuleCatalogueResponse response = ruleCatalogueService.createRule(command, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<RuleCatalogueResponse> submitForApproval(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_SUBMIT);
        return ResponseEntity.ok(ruleCatalogueService.submitForApproval(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<RuleCatalogueResponse> approveRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_APPROVE);
        String approverId = FinanceRuleSecurity.extractActorIdentity(jwt);
        return ResponseEntity.ok(ruleCatalogueService.approveRule(id, approverId));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<RuleCatalogueResponse> rejectRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody RejectRuleCatalogueCommand command) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_REJECT);
        String reviewerId = FinanceRuleSecurity.extractActorIdentity(jwt);
        return ResponseEntity.ok(ruleCatalogueService.rejectRule(id, reviewerId, command.reason()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<RuleCatalogueResponse> archiveRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_ARCHIVE);
        return ResponseEntity.ok(ruleCatalogueService.archiveRule(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuleCatalogueResponse> getRuleById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_READ);
        return ResponseEntity.ok(ruleCatalogueService.getRuleById(id));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<RuleCatalogueResponse> getRuleByCode(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String code) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_READ);
        return ResponseEntity.ok(ruleCatalogueService.getRuleByCode(code));
    }

    @GetMapping
    public ResponseEntity<List<RuleCatalogueResponse>> getRules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) RuleType type,
            @RequestParam(required = false) RuleStatus status) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_READ);
        return ResponseEntity.ok(ruleCatalogueService.getRules(type, status));
    }

    @GetMapping("/active")
    public ResponseEntity<RuleCatalogueResponse> getActiveEffectiveRule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam RuleType type,
            @RequestParam(required = false) Instant at) {
        FinanceRuleSecurity.assertAuthenticatedAndHasGrant(jwt, FinanceRuleSecurity.PERM_RULE_READ);
        return ruleCatalogueService.getActiveEffectiveRule(type, at)
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
}

