package com.vistay.finance.adapter.in.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    @Test
    @DisplayName("SCRUM-317: GraphQL schema contains strictly public metadata query and zero mutations or rule endpoints")
    void graphQLSchema_ContainsZeroMutationsOrSensitiveQueries() throws Exception {
        ClassPathResource resource = new ClassPathResource("graphql/schema.graphqls");
        assertThat(resource.exists()).isTrue();

        String schemaContent;
        try (InputStream is = resource.getInputStream()) {
            schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Schema MUST only expose financeServiceInfo query
        assertThat(schemaContent).contains("financeServiceInfo: FinanceServiceInfo!");
        assertThat(schemaContent).doesNotContain("Mutation");
        assertThat(schemaContent).doesNotContain("Subscription");
        assertThat(schemaContent).doesNotContain("rule");
        assertThat(schemaContent).doesNotContain("settlement");
        assertThat(schemaContent).doesNotContain("secret");
        assertThat(schemaContent).doesNotContain("password");
        assertThat(schemaContent).doesNotContain("token");
    }
}
