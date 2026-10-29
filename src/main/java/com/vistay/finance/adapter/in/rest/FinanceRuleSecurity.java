package com.vistay.finance.adapter.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

public final class FinanceRuleSecurity {

    public static final String PERM_RULE_CREATE = "finance:rules:create";
    public static final String PERM_RULE_SUBMIT = "finance:rules:submit";
    public static final String PERM_RULE_APPROVE = "finance:rules:approve";
    public static final String PERM_RULE_REJECT = "finance:rules:reject";
    public static final String PERM_RULE_ARCHIVE = "finance:rules:archive";
    public static final String PERM_RULE_READ = "finance:rules:read";

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_BRANCH = "BRANCH";
    public static final String SCOPE_OWN = "OWN";
    public static final String SCOPE_ASSIGNED = "ASSIGNED";

    private FinanceRuleSecurity() {
    }

    public static String extractActorIdentity(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authenticated principal");
        }
        String sub = jwt.getSubject();
        if (sub != null && !sub.isBlank()) {
            return sub.trim();
        }
        Object adminUserId = jwt.getClaims().get("admin_user_id");
        if (adminUserId != null && !Objects.toString(adminUserId, "").isBlank()) {
            return Objects.toString(adminUserId, "").trim();
        }
        Object accountId = jwt.getClaims().get("account_id");
        if (accountId != null && !Objects.toString(accountId, "").isBlank()) {
            return Objects.toString(accountId, "").trim();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Principal subject or actor id is missing in JWT claims");
    }

}
