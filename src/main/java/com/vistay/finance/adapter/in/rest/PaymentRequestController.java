package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.CreatePaymentRequestCommand;
import com.vistay.finance.application.dto.PaymentRequestResponse;
import com.vistay.finance.application.service.PaymentRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/requests")
public class PaymentRequestController {

    private final PaymentRequestService paymentRequestService;

    public PaymentRequestController(PaymentRequestService paymentRequestService) {
        this.paymentRequestService = paymentRequestService;
    }

    @PostMapping
    public ResponseEntity<PaymentRequestResponse> createPaymentRequest(@Valid @RequestBody CreatePaymentRequestCommand command) {
        PaymentRequestResponse response = paymentRequestService.createPaymentRequest(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentRequestResponse> getPaymentRequestById(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentRequestService.getPaymentRequestById(id));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<PaymentRequestResponse> getPaymentRequestByCode(@PathVariable String code) {
        return ResponseEntity.ok(paymentRequestService.getPaymentRequestByCode(code));
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<PaymentRequestResponse>> getPaymentRequestsByBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(paymentRequestService.getPaymentRequestsByBooking(bookingId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentRequestResponse> cancelPaymentRequest(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentRequestService.cancelPaymentRequest(id));
    }
}
