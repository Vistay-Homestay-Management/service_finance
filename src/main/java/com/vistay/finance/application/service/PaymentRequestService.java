package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.CreatePaymentRequestCommand;
import com.vistay.finance.application.dto.PaymentRequestResponse;
import com.vistay.finance.domain.model.PaymentRequest;
import com.vistay.finance.domain.repository.PaymentRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PaymentRequestService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PaymentRequestRepository paymentRequestRepository;

    public PaymentRequestService(PaymentRequestRepository paymentRequestRepository) {
        this.paymentRequestRepository = paymentRequestRepository;
    }

    @Transactional
    public PaymentRequestResponse createPaymentRequest(CreatePaymentRequestCommand command) {
        // Idempotency check: return existing if idempotency key matches
        var existing = paymentRequestRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent hit for payment request key: {}", command.idempotencyKey());
            return PaymentRequestResponse.fromEntity(existing.get());
        }

        String requestCode = generateRequestCode();
        PaymentRequest request = new PaymentRequest(
            UUID.randomUUID(),
            requestCode,
            command.bookingId(),
            command.payerType(),
            command.payerId(),
            command.amount(),
            command.currency(),
            command.purpose(),
            command.idempotencyKey(),
            command.expiresAt(),
            command.createdByAdminUserId()
        );

        PaymentRequest saved = paymentRequestRepository.save(request);
        log.info("Created payment request id: {}, code: {}, amount: {} {}", saved.getId(), saved.getRequestCode(), saved.getAmount(), saved.getCurrency());
        return PaymentRequestResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public PaymentRequestResponse getPaymentRequestById(UUID id) {
        return paymentRequestRepository.findById(id)
            .map(PaymentRequestResponse::fromEntity)
            .orElseThrow(() -> new NoSuchElementException("Payment request not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public PaymentRequestResponse getPaymentRequestByCode(String code) {
        return paymentRequestRepository.findByRequestCode(code)
            .map(PaymentRequestResponse::fromEntity)
            .orElseThrow(() -> new NoSuchElementException("Payment request not found with code: " + code));
    }

    @Transactional(readOnly = true)
    public List<PaymentRequestResponse> getPaymentRequestsByBooking(UUID bookingId) {
        return paymentRequestRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)
            .stream()
            .map(PaymentRequestResponse::fromEntity)
            .toList();
    }

    @Transactional
    public PaymentRequestResponse cancelPaymentRequest(UUID id) {
        PaymentRequest request = paymentRequestRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Payment request not found with id: " + id));

        request.markCancelled();
        PaymentRequest updated = paymentRequestRepository.save(request);
        log.info("Cancelled payment request id: {}", id);
        return PaymentRequestResponse.fromEntity(updated);
    }

    private String generateRequestCode() {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String randomPart = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "PAY-REQ-" + datePart + "-" + randomPart;
    }
}
