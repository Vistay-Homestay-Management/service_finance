package com.vistay.finance.application;

import com.vistay.contracts.property.v1.GetPropertyPartitionRecordRequest;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordResponse;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.contracts.property.v1.PropertyPartitionServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

@Component
public class FinancePropertyPartitionClient {
    private final PropertyLookup lookup;

    @Autowired
    public FinancePropertyPartitionClient(
        GrpcChannelFactory channels,
        @Value("${vistay.authorization.grpc-deadline:2s}") Duration deadline
    ) {
        var stub = PropertyPartitionServiceGrpc.newBlockingStub(channels.createChannel("property"));
        this.lookup = propertyId -> stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .getPropertyPartitionRecord(GetPropertyPartitionRecordRequest.newBuilder()
                .setPropertyId(propertyId.toString()).build());
    }

    FinancePropertyPartitionClient(PropertyLookup lookup) {
        this.lookup = lookup;
    }

    public Resolution resolve(UUID propertyId) {
        if (propertyId == null) {
            return Resolution.missing();
        }
        try {
            var response = lookup.get(propertyId);
            if (response.getResolutionStatus()
                == PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING) {
                return Resolution.missing();
            }
            if (response.getResolutionStatus()
                != PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED
                || !response.hasBranchId()) {
                throw unavailable("Property partition response is incomplete");
            }
            try {
                return Resolution.mapped(UUID.fromString(response.getBranchId()),
                    response.getMembershipVersion());
            } catch (IllegalArgumentException malformed) {
                throw unavailable("Property partition response is invalid");
            }
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Resolution.missing();
            }
            throw unavailable("Property partition service is unavailable");
        }
    }

    private static PropertyPartitionException unavailable(String message) {
        return new PropertyPartitionException("PROPERTY_PARTITION_UNAVAILABLE", message);
    }

    @FunctionalInterface
    interface PropertyLookup {
        GetPropertyPartitionRecordResponse get(UUID propertyId);
    }

    public record Resolution(UUID branchId, long membershipVersion) {
        public static Resolution mapped(UUID branchId, long membershipVersion) {
            return new Resolution(branchId, membershipVersion);
        }

        public static Resolution missing() {
            return new Resolution(null, 0);
        }

        public boolean mapped() {
            return branchId != null;
        }
    }

    public static final class PropertyPartitionException extends RuntimeException {
        private final String code;

        public PropertyPartitionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
