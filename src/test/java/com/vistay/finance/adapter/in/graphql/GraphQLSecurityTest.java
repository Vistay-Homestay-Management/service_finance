package com.vistay.finance.adapter.in.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphQLSecurityTest {

    private final ServiceInfoQuery serviceInfoQuery = new ServiceInfoQuery();

    @Test
    @DisplayName("SCRUM-317: Public service info query exposes only non-sensitive service identity")
    void financeServiceInfo_ExposesOnlyPublicMetadata() {
        ServiceInfoQuery.ServiceInfo info = serviceInfoQuery.financeServiceInfo();

        assertThat(info).isNotNull();
        assertThat(info.name()).isEqualTo("finance");
        assertThat(info.version()).isEqualTo("0.1.0");
    }
}

