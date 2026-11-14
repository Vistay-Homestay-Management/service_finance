package com.vistay.finance.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vistay.finance.domain.model.AmendmentAssessmentStatus;
import com.vistay.finance.domain.model.AmendmentRequiredAction;
import com.vistay.finance.domain.model.ReservationAmendmentAssessment;
import com.vistay.finance.domain.model.ReservationFieldGroup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Field-group-projected response for amendment financial assessment and final executability checks.
 * Baseline {@code RESERVATION_SUMMARY} omits monetary, fingerprint, and internal audit fields unless
 * explicitly granted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReservationAmendmentAssessmentResponse(
    UUID id,
    UUID bookingId,
    UUID propertyId,
    String proposalId,
    long amendmentRevision,
    boolean current,
    AmendmentRequiredAction requiredAction,
    AmendmentAssessmentStatus status,
    boolean executable,
    int ruleVersion,
    String currency,
    Instant expiresAt,
    Long version,
    Instant updatedAt,
    boolean replayed,
    BigDecimal beforeRequiredAmount,
    BigDecimal afterRequiredAmount,
    BigDecimal verifiedCollectedAmount,
    BigDecimal deltaAmount,
    UUID resultingReceivableId,
    UUID resultingRefundId,
    String beforeSnapshotFingerprint,
    String afterSnapshotFingerprint,
    String correlationId,
    UUID assessedByActorId,
    UUID approvedByAdminUserId,
    Instant approvedAt,
    UUID executedEventId,
    String waiverReason
) {
    public static ReservationAmendmentAssessmentResponse fromEntity(
        ReservationAmendmentAssessment entity,
        Set<ReservationFieldGroup> grantedGroups,
        boolean executable,
        boolean replayed
    ) {
        return fromEntityWithStatus(entity, entity.getStatus(), grantedGroups, executable, replayed);
    }

    public static ReservationAmendmentAssessmentResponse fromEntityWithStatus(
        ReservationAmendmentAssessment entity,
        AmendmentAssessmentStatus projectedStatus,
        Set<ReservationFieldGroup> grantedGroups,
        boolean executable,
        boolean replayed
    ) {
        Set<ReservationFieldGroup> groups = grantedGroups == null
            ? Set.of(ReservationFieldGroup.RESERVATION_SUMMARY)
            : grantedGroups;
        boolean financial = groups.contains(ReservationFieldGroup.FINANCIAL);
        boolean trustDetail = groups.contains(ReservationFieldGroup.TRUST_DETAIL);
        boolean internalAudit = groups.contains(ReservationFieldGroup.INTERNAL_AUDIT);

        return new ReservationAmendmentAssessmentResponse(
            entity.getId(),
            entity.getBookingId(),
            entity.getPropertyId(),
            entity.getProposalId(),
            entity.getAmendmentRevision(),
            entity.isCurrent(),
            entity.getRequiredAction(),
            projectedStatus,
            executable,
            entity.getRuleVersion(),
            entity.getCurrency(),
            entity.getExpiresAt(),
            entity.getVersion(),
            entity.getUpdatedAt(),
            replayed,
            financial ? entity.getBeforeRequiredAmount() : null,
            financial ? entity.getAfterRequiredAmount() : null,
            financial ? entity.getVerifiedCollectedAmount() : null,
            financial ? entity.getDeltaAmount() : null,
            financial ? entity.getResultingReceivableId() : null,
            financial ? entity.getResultingRefundId() : null,
            trustDetail ? entity.getBeforeSnapshotFingerprint() : null,
            trustDetail ? entity.getAfterSnapshotFingerprint() : null,
            internalAudit ? entity.getCorrelationId() : null,
            internalAudit ? entity.getAssessedByActorId() : null,
            internalAudit ? entity.getApprovedByAdminUserId() : null,
            internalAudit ? entity.getApprovedAt() : null,
            internalAudit ? entity.getExecutedEventId() : null,
            internalAudit ? entity.getWaiverReason() : null
        );
    }
}
