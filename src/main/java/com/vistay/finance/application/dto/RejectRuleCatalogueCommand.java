package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectRuleCatalogueCommand(
        @NotBlank @Size(max = 1000) String reason
) {
}

