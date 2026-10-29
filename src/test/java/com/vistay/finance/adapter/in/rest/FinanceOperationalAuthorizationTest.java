package com.vistay.finance.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceAuthorizationClient.AuthorizationException;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.FinanceBookingAuthorizationClient;
import com.vistay.finance.application.FinanceBookingAuthorizationClient.BookingAuthorizationException;
import com.vistay.finance.application.FinanceBookingAuthorizationClient.BookingRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class FinanceOperationalAuthorizationTest {
    private static final String PERMISSION = FinanceOperationalAuthorization.PAYMENT_READ;
    private final FinanceAuthorizationClient snapshots = mock(FinanceAuthorizationClient.class);
    private final FinanceBookingAuthorizationClient bookings = mock(FinanceBookingAuthorizationClient.class);
    private final FinanceOperationalAuthorization authorization =
        new FinanceOperationalAuthorization(snapshots, bookings);
    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @Test
    void allScopeDoesNotCallBookingResolver() {
        var actor = actor(Scope.ALL);

        assertThatCode(() -> authorization.requireBooking(actor, bookingId)).doesNotThrowAnyException();
        verify(bookings, never()).get(bookingId);
    }

    @Test
    void assignedScopeAcceptsDirectOrActiveAttribution() {
        var actor = actor(Scope.ASSIGNED);
        when(bookings.get(bookingId)).thenReturn(new BookingRecord(
            bookingId, UUID.randomUUID(), Set.of(profileId)));

        assertThatCode(() -> authorization.requireBooking(actor, bookingId)).doesNotThrowAnyException();
    }

    @Test
    void ownAndPartitionHaveNoPositiveFallback() {
        assertForbidden(() -> authorization.requireBooking(actor(Scope.OWN), bookingId));
        assertForbidden(() -> authorization.requireBooking(actor(Scope.PARTITION), bookingId));
        verify(bookings, never()).get(bookingId);
    }

    @Test
    void bookingDependencyFailureMapsToServiceUnavailable() {
        var actor = actor(Scope.ASSIGNED);
        when(bookings.get(bookingId)).thenThrow(new BookingAuthorizationException(
            "BOOKING_AUTHORIZATION_UNAVAILABLE", "unavailable"));

        assertThatThrownBy(() -> authorization.requireBooking(actor, bookingId))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void requiresExactPermissionAndReturnsProfileActor() {
        when(snapshots.require(accountId, PERMISSION)).thenReturn(snapshot(Scope.ALL));

        var actor = authorization.require(jwt(accountId.toString()), PERMISSION);

        assertThat(actor.profileId()).isEqualTo(profileId);
        verify(snapshots).require(accountId, PERMISSION);
    }

    @Test
    void authorizationDependencyFailureMapsToServiceUnavailable() {
        when(snapshots.require(accountId, PERMISSION)).thenThrow(
            new AuthorizationException("AUTHORIZATION_UNAVAILABLE", "unavailable"));

        assertThatThrownBy(() -> authorization.require(jwt(accountId.toString()), PERMISSION))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void rejectsActorSpoofingAndBindsAuthenticatedProfile() {
        var actor = actor(Scope.ALL);

        assertThat(authorization.bindActor(actor, null)).isEqualTo(profileId);
        assertThat(authorization.bindActor(actor, profileId)).isEqualTo(profileId);
        assertForbidden(() -> authorization.bindActor(actor, UUID.randomUUID()));
    }

    private FinanceOperationalAuthorization.Actor actor(Scope scope) {
        return new FinanceOperationalAuthorization.Actor(accountId, profileId, PERMISSION, snapshot(scope));
    }

    private Snapshot snapshot(Scope scope) {
        return new Snapshot(true, profileId, 7, Map.of(PERMISSION, Set.of(scope)), Map.of());
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private Jwt jwt(String subject) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
            Map.of("alg", "none"), Map.of("sub", subject, "aud", List.of("vistay-backoffice-api")));
    }
}
