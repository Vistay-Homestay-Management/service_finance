package com.vistay.finance.application.dto;

public record ApproveRuleCatalogueCommand(
        String note
) {
    public ApproveRuleCatalogueCommand() {
        this(null);
    }
}

