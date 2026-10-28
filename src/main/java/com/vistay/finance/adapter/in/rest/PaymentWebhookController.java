package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.PaymentTransactionResponse;
import com.vistay.finance.application.dto.ProcessPaymentWebhookCommand;
import com.vistay.finance.application.service.PaymentTransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PaymentWebhookController {

    private final PaymentTransactionService paymentTransactionService;

    public PaymentWebhookController(PaymentTransactionService paymentTransactionService) {
        this.paymentTransactionService = paymentTransactionService;
    }

    @PostMapping
    public ResponseEntity<PaymentTransactionResponse> handlePaymentWebhook(@Valid @RequestBody ProcessPaymentWebhookCommand command) {
        PaymentTransactionResponse response = paymentTransactionService.processPaymentWebhook(command);
        return ResponseEntity.ok(response);
    }
}
