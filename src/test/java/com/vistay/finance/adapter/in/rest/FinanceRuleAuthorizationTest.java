package com.vistay.finance.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceAuthorizationClient.AuthorizationException;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class FinanceRuleAuthorizationTest {
    private final FinanceAuthorizationClient client = mock(FinanceAuthorizationClient.class);
    private final FinanceRuleAuthorization authorization = new FinanceRuleAuthorization(client);
    private final UUID accountId = UUID.randomUUID();

    @Test
    void acceptsOnlyAllFromTheSharedSnapshot() {
        when(client.require(accountId, FinanceRuleSecurity.PERM_RULE_READ)).thenReturn(snapshot(Scope.ALL));

        assertThatCode(() -> authorization.requireAll(jwt(accountId.toString()),
            FinanceRuleSecurity.PERM_RULE_READ)).doesNotThrowAnyException();
    }

    @Test
    void rejectsOwnForTheGlobalCatalogue() {
        when(client.require(accountId, FinanceRuleSecurity.PERM_RULE_READ)).thenReturn(snapshot(Scope.OWN));

        assertThatThrownBy(() -> authorization.requireAll(jwt(accountId.toString()),
            FinanceRuleSecurity.PERM_RULE_READ))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void failsClosedWhenAuthorizationIsUnavailable() {
        when(client.require(accountId, FinanceRuleSecurity.PERM_RULE_READ))
            .thenThrow(new AuthorizationException("AUTHORIZATION_UNAVAILABLE", "unavailable"));

        assertThatThrownBy(() -> authorization.requireAll(jwt(accountId.toString()),
            FinanceRuleSecurity.PERM_RULE_READ))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void rejectsNonUuidTokenSubjectBeforeCallingAuthorization() {
        assertThatThrownBy(() -> authorization.requireAll(jwt("legacy-admin-name"),
            FinanceRuleSecurity.PERM_RULE_READ))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private Snapshot snapshot(Scope scope) {
        return new Snapshot(true, UUID.randomUUID(), 4,
            Map.of(FinanceRuleSecurity.PERM_RULE_READ, Set.of(scope)), Map.of());
    }

    private Jwt jwt(String subject) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
            Map.of("alg", "none"), Map.of("sub", subject, "aud", List.of("vistay-backoffice-api")));
    }
}
