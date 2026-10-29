package com.vistay.finance.application;

import com.vistay.contracts.booking.v1.BookingAuthorizationServiceGrpc;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordRequest;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

@Component
public class FinanceBookingAuthorizationClient {
    private final BookingLookup lookup;

    @Autowired
    public FinanceBookingAuthorizationClient(
        GrpcChannelFactory channels,
        @Value("${vistay.authorization.grpc-deadline:2s}") Duration deadline
    ) {
        var stub = BookingAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("booking"));
        this.lookup = bookingId -> stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .getBookingAuthorizationRecord(GetBookingAuthorizationRecordRequest.newBuilder()
                .setBookingId(bookingId.toString())
                .build());
    }

    FinanceBookingAuthorizationClient(BookingLookup lookup) {
        this.lookup = lookup;
    }

    public BookingRecord get(UUID bookingId) {
        if (bookingId == null) {
            throw new BookingAuthorizationException("BOOKING_ID_REQUIRED", "Booking id is required");
        }
        try {
            var response = lookup.get(bookingId);
            return new BookingRecord(
                UUID.fromString(response.getBookingId()),
                response.hasAssignedAdminUserId()
                    ? UUID.fromString(response.getAssignedAdminUserId()) : null,
                response.getActiveAttributedAdminUserIdsList().stream()
                    .map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet())
            );
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new BookingAuthorizationException("BOOKING_NOT_FOUND", "Booking record was not found");
            }
            throw new BookingAuthorizationException(
                "BOOKING_AUTHORIZATION_UNAVAILABLE", "Booking authorization service is unavailable");
        } catch (IllegalArgumentException invalidRecord) {
            throw new BookingAuthorizationException(
                "BOOKING_AUTHORIZATION_UNAVAILABLE", "Booking authorization record is invalid");
        }
    }

    @FunctionalInterface
    interface BookingLookup {
        GetBookingAuthorizationRecordResponse get(UUID bookingId);
    }

    public record BookingRecord(
        UUID bookingId,
        UUID assignedAdminUserId,
        Set<UUID> activeAttributedAdminUserIds
    ) {
        public boolean isAssignedTo(UUID adminUserId) {
            return adminUserId != null && (adminUserId.equals(assignedAdminUserId)
                || activeAttributedAdminUserIds.contains(adminUserId));
        }
    }

    public static final class BookingAuthorizationException extends RuntimeException {
        private final String code;

        public BookingAuthorizationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
