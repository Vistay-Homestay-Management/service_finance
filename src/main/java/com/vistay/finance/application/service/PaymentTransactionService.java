package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.PaymentAttemptRepository;
import com.vistay.finance.domain.repository.PaymentRequestRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PaymentTransactionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;

    public PaymentTransactionService(
        PaymentRequestRepository paymentRequestRepository,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentTransactionRepository paymentTransactionRepository,
        RefundRepository refundRepository
    ) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional
    public PaymentTransactionResponse processPaymentWebhook(ProcessPaymentWebhookCommand command) {
        // 1. Idempotency check: if transaction exists with this key, return it
        var existingTx = paymentTransactionRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existingTx.isPresent()) {
            log.info("Idempotent hit for payment webhook key: {}", command.idempotencyKey());
            return PaymentTransactionResponse.fromEntity(existingTx.get());
        }

        // 2. Load PaymentRequest
        PaymentRequest paymentRequest = paymentRequestRepository.findById(command.paymentRequestId())
            .orElseThrow(() -> new NoSuchElementException("Payment request not found: " + command.paymentRequestId()));

        // 3. Record Payment Attempt
        int nextAttemptNumber = paymentAttemptRepository.findMaxAttemptNumberByRequestId(paymentRequest.getId()) + 1;
        PaymentAttempt attempt = new PaymentAttempt(
            UUID.randomUUID(),
            paymentRequest,
            nextAttemptNumber,
            command.paymentMethod(),
            command.paymentProvider(),
            command.externalTransactionId(),
            command.paidAmount(),
            (command.currency() != null && !command.currency().isBlank()) ? command.currency() : paymentRequest.getCurrency()
        );
        attempt.markSucceeded(command.externalTransactionId());
        PaymentAttempt savedAttempt = paymentAttemptRepository.save(attempt);

        // 4. Create Immutable Ledger Transaction
        String transactionCode = generateTransactionCode("TXN");
        PaymentTransaction transaction = new PaymentTransaction(
            UUID.randomUUID(),
            transactionCode,
            paymentRequest.getId(),
            savedAttempt.getId(),
            null, // No original transaction for initial payment
            paymentRequest.getBookingId(),
            TransactionType.PAYMENT,
            command.paidAmount(),
            savedAttempt.getCurrency(),
            command.paymentMethod(),
            null, // payment account id
            command.paymentProvider(),
            command.externalTransactionId(),
            TransactionStatus.SUCCEEDED,
            command.idempotencyKey(),
            command.description() != null ? command.description() : "Webhook payment received via " + command.paymentProvider(),
            null
        );
        transaction.markSucceeded();
        PaymentTransaction savedTx = paymentTransactionRepository.save(transaction);

        // 5. Update Payment Request status based on cumulative payments
        BigDecimal totalPaid = paymentTransactionRepository.calculateTotalPaidAmountByRequestId(paymentRequest.getId());
        if (totalPaid.compareTo(paymentRequest.getAmount()) >= 0) {
            paymentRequest.markPaid();
        } else if (totalPaid.compareTo(BigDecimal.ZERO) > 0) {
            paymentRequest.markPartiallyPaid();
        }
        paymentRequestRepository.save(paymentRequest);

        log.info("Processed payment webhook successfully. TxCode: {}, Request: {}, Amount: {}",
            savedTx.getTransactionCode(), paymentRequest.getRequestCode(), savedTx.getAmount());

        return PaymentTransactionResponse.fromEntity(savedTx);
    }

    @Transactional
    public RefundResponse requestRefund(CreateRefundCommand command) {
        // Idempotency check
        var existingRefund = refundRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existingRefund.isPresent()) {
            return RefundResponse.fromEntity(existingRefund.get());
        }

        // Validate original transaction
        PaymentTransaction originalTx = paymentTransactionRepository.findById(command.originalTransactionId())
            .orElseThrow(() -> new NoSuchElementException("Original transaction not found: " + command.originalTransactionId()));

        if (originalTx.getTransactionType() != TransactionType.PAYMENT || originalTx.getStatus() != TransactionStatus.SUCCEEDED) {
            throw new IllegalStateException("Only succeeded payment transactions can be refunded");
        }

        if (command.amount().compareTo(originalTx.getAmount()) > 0) {
            throw new IllegalArgumentException("Refund amount cannot exceed original transaction amount");
        }

        String refundCode = generateTransactionCode("RFD");
        Refund refund = new Refund(
            UUID.randomUUID(),
            refundCode,
            command.bookingId() != null ? command.bookingId() : originalTx.getBookingId(),
            originalTx.getId(),
            command.amount(),
            command.currency() != null ? command.currency() : originalTx.getCurrency(),
            command.reason(),
            command.requestedByAdminUserId(),
            command.idempotencyKey()
        );

        Refund savedRefund = refundRepository.save(refund);
        log.info("Created refund request: {} for tx: {}", savedRefund.getRefundCode(), originalTx.getTransactionCode());
        return RefundResponse.fromEntity(savedRefund);
    }

    @Transactional
    public RefundResponse approveAndExecuteRefund(UUID refundId, UUID adminUserId) {
        Refund refund = refundRepository.findById(refundId)
            .orElseThrow(() -> new NoSuchElementException("Refund not found: " + refundId));

        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new IllegalStateException("Refund is not in REQUESTED status");
        }

        refund.approve(adminUserId);

        // Create REFUND transaction in ledger
        String refundTxCode = generateTransactionCode("TXN-RFD");
        PaymentTransaction refundTx = new PaymentTransaction(
            UUID.randomUUID(),
            refundTxCode,
            null,
            null,
            refund.getOriginalTransactionId(),
            refund.getBookingId(),
            TransactionType.REFUND,
            refund.getAmount(),
            refund.getCurrency(),
            PaymentMethod.BANK_TRANSFER,
            null,
            "SYSTEM",
            refund.getRefundCode(),
            TransactionStatus.SUCCEEDED,
            "refund-tx-" + refund.getId(),
            "Refund executed for " + refund.getRefundCode() + ": " + refund.getReason(),
            adminUserId
        );
        refundTx.markSucceeded();
        PaymentTransaction savedRefundTx = paymentTransactionRepository.save(refundTx);

        refund.markCompleted(savedRefundTx.getId());
        Refund updatedRefund = refundRepository.save(refund);

        log.info("Executed refund: {}, refundTxCode: {}", updatedRefund.getRefundCode(), savedRefundTx.getTransactionCode());
        return RefundResponse.fromEntity(updatedRefund);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransactionResponse> getTransactionsByBooking(UUID bookingId) {
        return paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId)
            .stream()
            .map(PaymentTransactionResponse::fromEntity)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsByBooking(UUID bookingId) {
        return refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId)
            .stream()
            .map(RefundResponse::fromEntity)
            .toList();
    }

    private String generateTransactionCode(String prefix) {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String randomPart = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return prefix + "-" + datePart + "-" + randomPart;
    }
}
