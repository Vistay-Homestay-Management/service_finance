package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceAuthorizationClient.AuthorizationException;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinanceRuleAuthorization {
    private final FinanceAuthorizationClient authorizations;

    public FinanceRuleAuthorization(FinanceAuthorizationClient authorizations) {
        this.authorizations = authorizations;
    }

    public void requireAll(Jwt jwt, String permission) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated principal");
        }
        final UUID accountId;
        try {
            accountId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject is not a ViStay account id");
        }
        try {
            var snapshot = authorizations.require(accountId, permission);
            if (!snapshot.scopes(permission).contains(Scope.ALL)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Finance rule catalogue requires " + permission + " with ALL scope");
            }
        } catch (AuthorizationException denied) {
            var status = "AUTHORIZATION_UNAVAILABLE".equals(denied.code())
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, denied.getMessage());
        }
    }
}
