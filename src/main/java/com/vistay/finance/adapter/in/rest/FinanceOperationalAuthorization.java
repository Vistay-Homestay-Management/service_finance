package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceAuthorizationClient.AuthorizationException;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.FinanceBookingAuthorizationClient;
import com.vistay.finance.application.FinanceBookingAuthorizationClient.BookingAuthorizationException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinanceOperationalAuthorization {
    public static final String PAYMENT_READ = "payment:read";
    public static final String PAYMENT_CREATE = "payment:create";
    public static final String PAYMENT_CANCEL = "payment:cancel";
    public static final String REFUND_READ = "refund:read";
    public static final String REFUND_REQUEST = "refund:request";
    public static final String REFUND_APPROVE = "refund:approve";

    private final FinanceAuthorizationClient authorizations;
    private final FinanceBookingAuthorizationClient bookings;

    public FinanceOperationalAuthorization(
        FinanceAuthorizationClient authorizations,
        FinanceBookingAuthorizationClient bookings
    ) {
        this.authorizations = authorizations;
        this.bookings = bookings;
    }

    public Actor require(Jwt jwt, String permission) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated principal");
        }
        final UUID accountId;
        try {
            accountId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "JWT subject is not a ViStay account id");
        }
        try {
            var snapshot = authorizations.require(accountId, permission);
            return new Actor(accountId, snapshot.profileId(), permission, snapshot);
        } catch (AuthorizationException denied) {
            var status = "AUTHORIZATION_UNAVAILABLE".equals(denied.code())
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, denied.getMessage());
        }
    }

    public void requireBooking(Actor actor, UUID bookingId) {
        var scopes = actor.snapshot().scopes(actor.permission());
        if (scopes.contains(Scope.ALL)) {
            return;
        }
        if (!scopes.contains(Scope.ASSIGNED)) {
            throw forbidden(actor.permission());
        }
        try {
            if (!bookings.get(bookingId).isAssignedTo(actor.profileId())) {
                throw forbidden(actor.permission());
            }
        } catch (BookingAuthorizationException failure) {
            if ("BOOKING_AUTHORIZATION_UNAVAILABLE".equals(failure.code())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage());
            }
            throw forbidden(actor.permission());
        }
    }

    public UUID bindActor(Actor actor, UUID suppliedAdminUserId) {
        if (suppliedAdminUserId != null && !suppliedAdminUserId.equals(actor.profileId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Administrator actor id does not match the authenticated profile");
        }
        return actor.profileId();
    }

    private ResponseStatusException forbidden(String permission) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Record is outside the authorized scope for " + permission);
    }

    public record Actor(UUID accountId, UUID profileId, String permission, Snapshot snapshot) {}
}
