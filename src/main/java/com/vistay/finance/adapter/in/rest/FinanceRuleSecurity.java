package com.vistay.finance.adapter.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FinanceRuleSecurity {

    public static final String PERM_RULE_CREATE = "finance:rules:create";
    public static final String PERM_RULE_SUBMIT = "finance:rules:submit";
    public static final String PERM_RULE_APPROVE = "finance:rules:approve";
    public static final String PERM_RULE_REJECT = "finance:rules:reject";
    public static final String PERM_RULE_ARCHIVE = "finance:rules:archive";
    public static final String PERM_RULE_READ = "finance:rules:read";

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

    public static void assertAuthenticatedAndHasGrant(Jwt jwt, String requiredPermission) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authenticated principal");
        }
        if (!hasGrant(jwt, requiredPermission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: Missing required permission grant '" + requiredPermission + "'");
        }
    }

    public static boolean hasGrant(Jwt jwt, String requiredPermission) {
        if (jwt == null) {
            return false;
        }

        // 1. Check ViStay fine-grained 'grants' claim: List<Map<p, s>> or Map
        Object grantsObj = jwt.getClaims().get("grants");
        if (grantsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object p = map.get("p");
                    Object perm = map.get("permission");
                    if (matchesPermission(p, requiredPermission) || matchesPermission(perm, requiredPermission)) {
                        return true;
                    }
                } else if (matchesPermission(item, requiredPermission)) {
                    return true;
                }
            }
        } else if (grantsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (matchesPermission(entry.getKey(), requiredPermission)
                        || matchesPermission(entry.getValue(), requiredPermission)) {
                    return true;
                }
            }
        }

        // 2. Check standard 'scope' or 'scp' claim
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String s : scope.split("\\s+")) {
                if (matchesPermission(s, requiredPermission)) {
                    return true;
                }
            }
        }

        // 3. Check Spring Security authorities from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                if (matchesPermission(ga.getAuthority(), requiredPermission)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesPermission(Object candidate, String target) {
        if (candidate == null) {
            return false;
        }
        String candStr = Objects.toString(candidate, "").trim();
        if (target.equalsIgnoreCase(candStr)) {
            return true;
        }
        if (candStr.startsWith("SCOPE_") && target.equalsIgnoreCase(candStr.substring("SCOPE_".length()))) {
            return true;
        }
        return false;
    }
}

