package com.vistay.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordResponse;
import com.vistay.finance.application.FinanceBookingAuthorizationClient.BookingAuthorizationException;
import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinanceBookingAuthorizationClientTest {
    private final UUID bookingId = UUID.randomUUID();

    @Test
    void mapsDirectAndActiveAssignments() {
        var direct = UUID.randomUUID();
        var active = UUID.randomUUID();
        var client = new FinanceBookingAuthorizationClient(ignored ->
            GetBookingAuthorizationRecordResponse.newBuilder()
                .setBookingId(bookingId.toString())
                .setAssignedAdminUserId(direct.toString())
                .addActiveAttributedAdminUserIds(active.toString())
                .build());

        var record = client.get(bookingId);

        assertThat(record.isAssignedTo(direct)).isTrue();
        assertThat(record.isAssignedTo(active)).isTrue();
        assertThat(record.isAssignedTo(UUID.randomUUID())).isFalse();
    }

    @Test
    void distinguishesMissingBookingFromDependencyFailure() {
        var missing = new FinanceBookingAuthorizationClient(ignored -> {
            throw Status.NOT_FOUND.asRuntimeException();
        });
        var unavailable = new FinanceBookingAuthorizationClient(ignored -> {
            throw Status.UNAVAILABLE.asRuntimeException();
        });

        assertThatThrownBy(() -> missing.get(bookingId))
            .isInstanceOfSatisfying(BookingAuthorizationException.class,
                error -> assertThat(error.code()).isEqualTo("BOOKING_NOT_FOUND"));
        assertThatThrownBy(() -> unavailable.get(bookingId))
            .isInstanceOfSatisfying(BookingAuthorizationException.class,
                error -> assertThat(error.code()).isEqualTo("BOOKING_AUTHORIZATION_UNAVAILABLE"));
    }

    @Test
    void rejectsMalformedAuthoritativeRecordsAsUnavailable() {
        var client = new FinanceBookingAuthorizationClient(ignored ->
            GetBookingAuthorizationRecordResponse.newBuilder().setBookingId("not-a-uuid").build());

        assertThatThrownBy(() -> client.get(bookingId))
            .isInstanceOfSatisfying(BookingAuthorizationException.class,
                error -> assertThat(error.code()).isEqualTo("BOOKING_AUTHORIZATION_UNAVAILABLE"));
    }
}
