package com.vistay.finance.adapter.in.graphql;

import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.application.dto.FinanceDashboardSummaryResponse;
import com.vistay.finance.application.service.SettlementPeriodService;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/** Finance-owned projection; no cross-service database reads. */
@Controller
public class FinanceDashboardQuery {
    private final SettlementPeriodService settlements;
    private final FinanceOperationalAuthorization authorization;

    public FinanceDashboardQuery(SettlementPeriodService settlements,
        FinanceOperationalAuthorization authorization) {
        this.settlements = settlements;
        this.authorization = authorization;
    }

    @QueryMapping
    @RequiresPermission(FinanceOperationalAuthorization.FINANCE_READ)
    public FinanceDashboardSummaryResponse financeDashboardSummary(
        @AuthenticationPrincipal Jwt jwt) {
        authorization.requireAll(jwt, FinanceOperationalAuthorization.FINANCE_READ);
        return settlements.getDashboardSummary();
    }
}
