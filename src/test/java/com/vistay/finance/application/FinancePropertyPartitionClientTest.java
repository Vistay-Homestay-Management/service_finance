package com.vistay.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.contracts.property.v1.GetPropertyPartitionRecordResponse;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.finance.application.FinancePropertyPartitionClient.PropertyPartitionException;
import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancePropertyPartitionClientTest {
    private final UUID propertyId = UUID.randomUUID();

    @Test
    void resolvesMappedAndMissingStates() {
        var branchId = UUID.randomUUID();
        var mappedResponse = GetPropertyPartitionRecordResponse.newBuilder()
            .setPropertyId(propertyId.toString())
            .setResolutionStatus(
                PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
            .setBranchId(branchId.toString())
            .setMembershipVersion(9)
            .build();
        var mapped = new FinancePropertyPartitionClient(ignored -> mappedResponse)
            .resolve(propertyId);
        assertThat(mapped.branchId()).isEqualTo(branchId);
        assertThat(mapped.membershipVersion()).isEqualTo(9);

        var missingResponse = mappedResponse.toBuilder().clearBranchId()
            .setResolutionStatus(
                PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING)
            .build();
        assertThat(new FinancePropertyPartitionClient(ignored -> missingResponse)
            .resolve(propertyId).mapped()).isFalse();
    }

    @Test
    void mapsUnknownPropertyToMissingAndDependencyFailureToUnavailable() {
        var unknown = new FinancePropertyPartitionClient(ignored -> {
            throw Status.NOT_FOUND.asRuntimeException();
        });
        assertThat(unknown.resolve(propertyId).mapped()).isFalse();

        var unavailable = new FinancePropertyPartitionClient(ignored -> {
            throw Status.UNAVAILABLE.asRuntimeException();
        });
        assertThatThrownBy(() -> unavailable.resolve(propertyId))
            .isInstanceOf(PropertyPartitionException.class);
    }

    @Test
    void malformedMappedResponseFailsAsUnavailable() {
        var malformed = GetPropertyPartitionRecordResponse.newBuilder()
            .setPropertyId(propertyId.toString())
            .setResolutionStatus(
                PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
            .setBranchId("not-a-uuid")
            .build();

        assertThatThrownBy(() -> new FinancePropertyPartitionClient(ignored -> malformed)
            .resolve(propertyId)).isInstanceOf(PropertyPartitionException.class);
    }
}
