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
import java.util.Set;

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

    private static final Set<String> VALID_SCOPES = Set.of(SCOPE_ALL, SCOPE_BRANCH, SCOPE_OWN, SCOPE_ASSIGNED);

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

    public static void assertAuthenticatedAndHasGrant(Jwt jwt, String requiredPermission, String requiredScope) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authenticated principal");
        }
        if (!hasGrant(jwt, requiredPermission, requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: Missing required grant (" + requiredPermission + " with scope " + requiredScope + ")");
        }
    }

    public static void assertAuthenticatedAndHasReadGrant(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authenticated principal");
        }
        if (!hasReadGrant(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: Missing required permission grant '" + PERM_RULE_READ + "'");
        }
    }

    public static boolean hasGrant(Jwt jwt, String requiredPermission, String requiredScope) {
        if (jwt == null || requiredPermission == null || requiredScope == null) {
            return false;
        }

        // 1. Check ViStay fine-grained 'grants' claim: List<Map<p, s>>
        // Each grant item must match BOTH permission AND scope atomically!
        Object grantsObj = jwt.getClaims().get("grants");
        if (grantsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object p = map.get("p");
                    Object perm = map.get("permission");
                    Object s = map.get("s");
                    Object scope = map.get("scope");

                    String permVal = (p != null) ? p.toString() : (perm != null ? perm.toString() : null);
                    String scopeVal = (s != null) ? s.toString() : (scope != null ? scope.toString() : null);

                    if (matchesPermission(permVal, requiredPermission) && matchesScope(scopeVal, requiredScope)) {
                        return true;
                    }
                }
            }
        }

        // 2. Check Spring Security authorities from SecurityContext (e.g. SCOPE_finance:rules:create_ALL)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            String targetAuthority = requiredPermission + "_" + requiredScope;
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String authStr = ga.getAuthority();
                if (targetAuthority.equalsIgnoreCase(authStr)
                        || ("SCOPE_" + targetAuthority).equalsIgnoreCase(authStr)) {
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean hasReadGrant(Jwt jwt) {
        if (jwt == null) {
            return false;
        }

        Object grantsObj = jwt.getClaims().get("grants");
        if (grantsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object p = map.get("p");
                    Object perm = map.get("permission");
                    Object s = map.get("s");
                    Object scope = map.get("scope");

                    String permVal = (p != null) ? p.toString() : (perm != null ? perm.toString() : null);
                    String scopeVal = (s != null) ? s.toString() : (scope != null ? scope.toString() : null);

                    if (matchesPermission(permVal, PERM_RULE_READ) && scopeVal != null && VALID_SCOPES.contains(scopeVal.trim().toUpperCase())) {
                        return true;
                    }
                }
            }
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String authStr = ga.getAuthority();
                for (String scope : VALID_SCOPES) {
                    String targetAuthority = PERM_RULE_READ + "_" + scope;
                    if (targetAuthority.equalsIgnoreCase(authStr)
                            || ("SCOPE_" + targetAuthority).equalsIgnoreCase(authStr)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean matchesPermission(Object candidate, String target) {
        if (candidate == null || target == null) {
            return false;
        }
        String candStr = Objects.toString(candidate, "").trim();
        return target.equalsIgnoreCase(candStr);
    }

    private static boolean matchesScope(Object candidate, String target) {
        if (candidate == null || target == null) {
            return false;
        }
        String candStr = Objects.toString(candidate, "").trim();
        return target.equalsIgnoreCase(candStr);
    }
}
