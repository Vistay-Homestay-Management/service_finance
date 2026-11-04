package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceAuthorizationClient.AuthorizationException;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.FinanceBookingAuthorizationClient;
import com.vistay.finance.application.FinanceBookingAuthorizationClient.BookingAuthorizationException;
import com.vistay.finance.application.FinancePropertyPartitionClient;
import com.vistay.finance.application.FinancePropertyPartitionClient.PropertyPartitionException;
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
    public static final String SETTLEMENT_CREATE = "settlement:create";
    public static final String SETTLEMENT_READ = "settlement:read";
    public static final String SETTLEMENT_UPDATE = "settlement:update";
    public static final String SETTLEMENT_APPROVE = "settlement:approve";
    public static final String SETTLEMENT_PAYOUT = "settlement:payout";
    public static final String SETTLEMENT_EXPORT = "settlement:export";
    public static final String FINANCE_READ = "finance:read";
    public static final String FINANCE_EXPORT = "finance:export";

    private final FinanceAuthorizationClient authorizations;
    private final FinanceBookingAuthorizationClient bookings;
    private final FinancePropertyPartitionClient propertyPartitions;

    public FinanceOperationalAuthorization(
        FinanceAuthorizationClient authorizations,
        FinanceBookingAuthorizationClient bookings,
        FinancePropertyPartitionClient propertyPartitions
    ) {
        this.authorizations = authorizations;
        this.bookings = bookings;
        this.propertyPartitions = propertyPartitions;
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

    public Actor requireAll(Jwt jwt, String permission) {
        var actor = require(jwt, permission);
        if (!actor.snapshot().scopes(permission).contains(Scope.ALL)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                permission + " requires ALL scope until an authoritative record resolver is available");
        }
        return actor;
    }

    public void requireProperty(Actor actor, UUID propertyId) {
        requireProperty(propertyId, actor);
    }

    public void requireProperty(UUID propertyId, Actor... actors) {
        boolean resolutionRequired = false;
        for (var actor : actors) {
            var scopes = actor.snapshot().scopes(actor.permission());
            if (scopes.contains(Scope.ALL)) {
                continue;
            }
            if (!scopes.contains(Scope.PARTITION)) {
                throw forbidden(actor.permission());
            }
            resolutionRequired = true;
        }
        if (!resolutionRequired) {
            return;
        }
        try {
            var resolution = propertyPartitions.resolve(propertyId);
            if (!resolution.mapped()) {
                throw forbidden(actors[0].permission());
            }
            for (var actor : actors) {
                if (actor.snapshot().scopes(actor.permission()).contains(Scope.ALL)) {
                    continue;
                }
                boolean granted = actor.snapshot().partitions(actor.permission()).stream()
                    .anyMatch(binding -> "BRANCH".equals(binding.partitionType())
                        && resolution.branchId().equals(binding.partitionId()));
                if (!granted) {
                    throw forbidden(actor.permission());
                }
            }
        } catch (PropertyPartitionException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                unavailable.getMessage(), unavailable);
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
