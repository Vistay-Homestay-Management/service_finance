package com.vistay.finance.application.dto;

public record SubmitRuleCatalogueCommand(
        String note
) {
    public SubmitRuleCatalogueCommand() {
        this(null);
    }
}

