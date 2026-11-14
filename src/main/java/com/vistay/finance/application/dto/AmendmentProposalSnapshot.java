package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/** Immutable commercial snapshot used to fingerprint before/after reservation amendment inputs. */
public record AmendmentProposalSnapshot(
    @NotNull UUID propertyId,
    @NotNull UUID unitId,
    UUID customerId,
    @NotNull LocalDate checkInDate,
    @NotNull LocalDate checkOutDate,
    @NotBlank String stayProduct,
    @NotNull @PositiveOrZero BigDecimal lodgingAmount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    Long trustInputVersion,
    boolean manualWaiverRequested
) {
    /** Produces a deterministic 64-char SHA-256 hex digest without PII fields. */
    public String canonicalSha256() {
        String normalizedCurrency = currency.trim().toUpperCase();
        String canonical = String.join("|",
            propertyId.toString(),
            unitId.toString(),
            customerId != null ? customerId.toString() : "NONE",
            checkInDate.toString(),
            checkOutDate.toString(),
            stayProduct.trim().toUpperCase(),
            lodgingAmount.stripTrailingZeros().toPlainString(),
            normalizedCurrency,
            trustInputVersion != null ? trustInputVersion.toString() : "NONE",
            Boolean.toString(manualWaiverRequested)
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
