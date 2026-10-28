package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.CreateRefundCommand;
import com.vistay.finance.application.dto.RefundResponse;
import com.vistay.finance.application.service.PaymentTransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/refunds")
public class RefundController {

    private final PaymentTransactionService paymentTransactionService;

    public RefundController(PaymentTransactionService paymentTransactionService) {
        this.paymentTransactionService = paymentTransactionService;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> requestRefund(@Valid @RequestBody CreateRefundCommand command) {
        RefundResponse response = paymentTransactionService.requestRefund(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<RefundResponse> approveRefund(
        @PathVariable UUID id,
        @RequestParam UUID adminUserId
    ) {
        RefundResponse response = paymentTransactionService.approveAndExecuteRefund(id, adminUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<RefundResponse>> getRefundsByBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(paymentTransactionService.getRefundsByBooking(bookingId));
    }
}
