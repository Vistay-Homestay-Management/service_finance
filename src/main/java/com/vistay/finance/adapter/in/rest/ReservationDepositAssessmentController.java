package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.AssessDepositCommand;
import com.vistay.finance.application.dto.DepositAssessmentResponse;
import com.vistay.finance.application.dto.WaiveDepositCommand;
import com.vistay.finance.application.service.ReservationDepositAssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/deposit")
public class ReservationDepositAssessmentController {

    public static final String DEPOSIT_ASSESS = "finance:deposit:assess";
    public static final String DEPOSIT_WAIVE = "finance:deposit:waive";
    public static final String DEPOSIT_READ = "finance:deposit:read";

    private final ReservationDepositAssessmentService assessmentService;
    private final FinanceOperationalAuthorization authorization;

    public ReservationDepositAssessmentController(ReservationDepositAssessmentService assessmentService,
                                                  FinanceOperationalAuthorization authorization) {
        this.assessmentService = assessmentService;
        this.authorization = authorization;
    }

    @PostMapping("/assess")
    public ResponseEntity<DepositAssessmentResponse> assessDeposit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AssessDepositCommand command
    ) {
        var actor = authorization.require(jwt, DEPOSIT_ASSESS);
        authorization.requireProperty(actor, command.propertyId());
        if (command.bookingId() != null) {
            authorization.requireBooking(actor, command.bookingId());
        }

        DepositAssessmentResponse response = assessmentService.assessDeposit(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/waive")
    public ResponseEntity<DepositAssessmentResponse> waiveDeposit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody WaiveDepositCommand command
    ) {
        var actor = authorization.require(jwt, DEPOSIT_WAIVE);
        var boundCommand = new WaiveDepositCommand(
                command.targetKey(),
                authorization.bindActor(actor, command.approverAdminId()),
                command.reason(),
                command.idempotencyKey(),
                command.correlationId()
        );

        DepositAssessmentResponse response = assessmentService.waiveDeposit(boundCommand);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/decision")
    public ResponseEntity<DepositAssessmentResponse> getDecision(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String targetKey,
            @RequestParam(required = false) UUID bookingId
    ) {
        var actor = authorization.require(jwt, DEPOSIT_READ);

        if (targetKey != null && !targetKey.isBlank()) {
            DepositAssessmentResponse response = assessmentService.getCurrentDecision(targetKey);
            authorization.requireProperty(actor, response.propertyId());
            if (response.bookingId() != null) {
                authorization.requireBooking(actor, response.bookingId());
            }
            return ResponseEntity.ok(response);
        } else if (bookingId != null) {
            authorization.requireBooking(actor, bookingId);
            DepositAssessmentResponse response = assessmentService.getCurrentDecisionByBookingId(bookingId);
            authorization.requireProperty(actor, response.propertyId());
            return ResponseEntity.ok(response);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either targetKey or bookingId must be provided");
        }
    }
}
