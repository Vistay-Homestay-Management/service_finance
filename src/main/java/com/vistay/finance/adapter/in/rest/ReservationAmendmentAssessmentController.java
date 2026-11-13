package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.ApproveAmendmentWaiverCommand;
import com.vistay.finance.application.dto.AssessReservationAmendmentCommand;
import com.vistay.finance.application.dto.ReservationAmendmentAssessmentResponse;
import com.vistay.finance.application.service.ReservationAmendmentAssessmentService;
import com.vistay.finance.domain.model.ReservationFieldGroup;
import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance/reservation-amendments")
public class ReservationAmendmentAssessmentController {

    private final ReservationAmendmentAssessmentService assessmentService;
    private final FinanceOperationalAuthorization authorization;

    public ReservationAmendmentAssessmentController(
        ReservationAmendmentAssessmentService assessmentService,
        FinanceOperationalAuthorization authorization
    ) {
        this.assessmentService = assessmentService;
        this.authorization = authorization;
    }

    @PostMapping("/assess")
    public ResponseEntity<ReservationAmendmentAssessmentResponse> assessAmendment(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody AssessReservationAmendmentCommand command,
        @RequestHeader(value = "X-Field-Groups", required = false) String fieldGroupsHeader
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS);
        authorization.requireReservationAccess(
            actor,
            command.bookingId(),
            command.afterSnapshot().propertyId(),
            command.afterSnapshot().customerId()
        );

        Set<ReservationFieldGroup> grantedGroups = parseFieldGroups(fieldGroupsHeader);
        ReservationAmendmentAssessmentResponse response = assessmentService.assessAmendment(
            command,
            actor.profileId(),
            grantedGroups
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/decision")
    public ResponseEntity<ReservationAmendmentAssessmentResponse> getDecision(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID bookingId,
        @RequestParam String proposalId,
        @RequestParam(required = false) UUID propertyId,
        @RequestParam(required = false) UUID customerId,
        @RequestParam(required = false) String expectedFingerprint,
        @RequestParam(required = false) Long expectedVersion,
        @RequestHeader(value = "X-Field-Groups", required = false) String fieldGroupsHeader
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        authorization.requireReservationAccess(actor, bookingId, propertyId, customerId);

        Set<ReservationFieldGroup> grantedGroups = parseFieldGroups(fieldGroupsHeader);
        ReservationAmendmentAssessmentResponse response = assessmentService.getFinalDecision(
            bookingId,
            proposalId,
            expectedFingerprint,
            expectedVersion,
            grantedGroups
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-collection")
    public ResponseEntity<ReservationAmendmentAssessmentResponse> verifyCollection(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID bookingId,
        @RequestParam String proposalId,
        @RequestParam(required = false) UUID propertyId,
        @RequestParam(required = false) UUID customerId,
        @RequestParam(required = false) Long expectedVersion,
        @RequestParam String idempotencyKey,
        @RequestParam(required = false) String correlationId,
        @RequestHeader(value = "X-Field-Groups", required = false) String fieldGroupsHeader
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        authorization.requireReservationAccess(actor, bookingId, propertyId, customerId);

        Set<ReservationFieldGroup> grantedGroups = parseFieldGroups(fieldGroupsHeader);
        ReservationAmendmentAssessmentResponse response = assessmentService.verifyCollection(
            bookingId,
            proposalId,
            expectedVersion,
            idempotencyKey,
            correlationId,
            actor.profileId(),
            grantedGroups
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/waiver")
    public ResponseEntity<ReservationAmendmentAssessmentResponse> approveWaiver(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ApproveAmendmentWaiverCommand command,
        @RequestHeader(value = "X-Field-Groups", required = false) String fieldGroupsHeader
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.DEPOSIT_WAIVE);
        authorization.requireReservationAccess(actor, command.bookingId(), null, null);
        UUID adminUserId = authorization.bindActor(actor, command.approvedByAdminUserId());

        Set<ReservationFieldGroup> grantedGroups = parseFieldGroups(fieldGroupsHeader);
        ReservationAmendmentAssessmentResponse response = assessmentService.approveWaiver(
            command,
            adminUserId,
            grantedGroups
        );

        return ResponseEntity.ok(response);
    }

    private static Set<ReservationFieldGroup> parseFieldGroups(String header) {
        if (header == null || header.isBlank()) {
            return Set.of(
                ReservationFieldGroup.RESERVATION_SUMMARY,
                ReservationFieldGroup.FINANCIAL,
                ReservationFieldGroup.TRUST_DETAIL,
                ReservationFieldGroup.INTERNAL_AUDIT
            );
        }
        Set<ReservationFieldGroup> groups = new HashSet<>();
        for (String part : header.split(",")) {
            try {
                groups.add(ReservationFieldGroup.valueOf(part.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (groups.isEmpty()) {
            groups.add(ReservationFieldGroup.RESERVATION_SUMMARY);
        }
        return Set.copyOf(groups);
    }
}
