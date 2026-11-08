package com.vistay.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceDashboardGraphqlContractTest {
    @Test
    void financeSummaryIsPermissionPinnedAndServiceOwned() throws Exception {
        var source = Files.readString(Path.of(
            "src/main/java/com/vistay/finance/adapter/in/graphql/FinanceDashboardQuery.java"));
        var schema = Files.readString(Path.of("src/main/resources/graphql/schema.graphqls"));
        assertThat(source).contains("@RequiresPermission(FinanceOperationalAuthorization.FINANCE_READ)",
            "authorization.requireAll", "settlements.getDashboardSummary()");
        assertThat(schema).contains("financeDashboardSummary", "FinanceDashboardSummary");
    }
}
