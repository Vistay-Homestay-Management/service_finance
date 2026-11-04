package com.vistay.finance.application.dto;

import java.util.UUID;

public record MakerCheckerApprovalCommand(
    UUID approverAdminUserId,
    String notes
) {}

