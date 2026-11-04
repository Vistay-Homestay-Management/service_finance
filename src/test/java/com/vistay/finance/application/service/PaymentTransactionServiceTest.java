package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.CreateRefundCommand;
import com.vistay.finance.application.dto.PaymentTransactionResponse;
import com.vistay.finance.application.dto.ProcessPaymentWebhookCommand;
import com.vistay.finance.application.dto.RefundResponse;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.PaymentAttemptRepository;
import com.vistay.finance.domain.repository.PaymentRequestRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;
    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private RefundRepository refundRepository;

    private PaymentTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentTransactionService(
            paymentRequestRepository,
            paymentAttemptRepository,
            paymentTransactionRepository,
            refundRepository
        );
    }

    @Test
    @DisplayName("Xử lý Webhook thanh toán thành công và cập nhật trạng thái PAID cho yêu cầu")
    void processPaymentWebhook_Success_FullPayment() {
        UUID requestId = UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(
            requestId,
            "PAY-REQ-001",
            UUID.randomUUID(),
            PayerType.CUSTOMER,
            UUID.randomUUID(),
            new BigDecimal("1000000.00"),
            "VND",
            "Thanh toán",
            "idemp-req-001",
            null,
            null
        );

        ProcessPaymentWebhookCommand command = new ProcessPaymentWebhookCommand(
            requestId,
            PaymentMethod.BANK_TRANSFER,
            "VIETQR",
            "VQR-99999",
            new BigDecimal("1000000.00"),
            "VND",
            "webhook-key-001",
            "Thanh toan VietQR qua MBBank"
        );

        when(paymentTransactionRepository.findByIdempotencyKey("webhook-key-001")).thenReturn(Optional.empty());
        when(paymentRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(paymentAttemptRepository.findMaxAttemptNumberByRequestId(requestId)).thenReturn(0);
        when(paymentAttemptRepository.save(any(PaymentAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentTransactionRepository.calculateTotalPaidAmountByRequestId(requestId)).thenReturn(new BigDecimal("1000000.00"));

        PaymentTransactionResponse response = service.processPaymentWebhook(command);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(response.transactionType()).isEqualTo(TransactionType.PAYMENT);
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.PAID);

        verify(paymentTransactionRepository, times(1)).save(any(PaymentTransaction.class));
        verify(paymentRequestRepository, times(1)).save(request);
    }

    @Test
    @DisplayName("Đảm bảo Idempotency: Webhook gọi lại không tạo thêm giao dịch trùng lặp")
    void processPaymentWebhook_Idempotent() {
        String idempotencyKey = "webhook-key-repeat";
        PaymentTransaction existingTx = new PaymentTransaction(
            UUID.randomUUID(),
            "TXN-EXISTING",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            TransactionType.PAYMENT,
            new BigDecimal("500000.00"),
            "VND",
            PaymentMethod.BANK_TRANSFER,
            null,
            "VIETQR",
            "EXT-123",
            TransactionStatus.SUCCEEDED,
            idempotencyKey,
            "Da thanh toan",
            null
        );

        when(paymentTransactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTx));

        ProcessPaymentWebhookCommand command = new ProcessPaymentWebhookCommand(
            existingTx.getPaymentRequestId(),
            PaymentMethod.BANK_TRANSFER,
            "VIETQR",
            "EXT-123",
            new BigDecimal("500000.00"),
            "VND",
            idempotencyKey,
            "Goi lai webhook"
        );

        PaymentTransactionResponse response = service.processPaymentWebhook(command);

        assertThat(response.transactionCode()).isEqualTo("TXN-EXISTING");
        verify(paymentRequestRepository, never()).findById(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tạo yêu cầu hoàn tiền thành công")
    void requestRefund_Success() {
        UUID txId = UUID.randomUUID();
        PaymentTransaction originalTx = new PaymentTransaction(
            txId,
            "TXN-PAY-001",
            UUID.randomUUID(),
            null,
            null,
            UUID.randomUUID(),
            TransactionType.PAYMENT,
            new BigDecimal("1000000.00"),
            "VND",
            PaymentMethod.BANK_TRANSFER,
            null,
            "VIETQR",
            "EXT-1",
            TransactionStatus.SUCCEEDED,
            "key-tx-1",
            "Thanh toan",
            null
        );

        CreateRefundCommand command = new CreateRefundCommand(
            originalTx.getBookingId(),
            txId,
            new BigDecimal("500000.00"),
            "VND",
            "Khách hủy sớm theo chính sách",
            UUID.randomUUID(),
            "rfd-key-01"
        );

        when(refundRepository.findByIdempotencyKey("rfd-key-01")).thenReturn(Optional.empty());
        when(paymentTransactionRepository.findById(txId)).thenReturn(Optional.of(originalTx));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundResponse response = service.requestRefund(command);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("500000.00"));
        assertThat(response.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(response.refundCode()).startsWith("RFD-");

        verify(refundRepository, times(1)).save(any(Refund.class));
    }

    @Test
    @DisplayName("Ngoại lệ khi số tiền hoàn vượt quá số tiền giao dịch gốc")
    void requestRefund_FailsWhenAmountExceedsOriginal() {
        UUID txId = UUID.randomUUID();
        PaymentTransaction originalTx = new PaymentTransaction(
            txId,
            "TXN-PAY-001",
            UUID.randomUUID(),
            null,
            null,
            UUID.randomUUID(),
            TransactionType.PAYMENT,
            new BigDecimal("1000000.00"),
            "VND",
            PaymentMethod.BANK_TRANSFER,
            null,
            "VIETQR",
            "EXT-1",
            TransactionStatus.SUCCEEDED,
            "key-tx-1",
            "Thanh toan",
            null
        );

        CreateRefundCommand command = new CreateRefundCommand(
            originalTx.getBookingId(),
            txId,
            new BigDecimal("1500000.00"), // Vượt quá 1.000.000
            "VND",
            "Khách đòi hoàn nhiều hơn",
            UUID.randomUUID(),
            "rfd-key-invalid"
        );

        when(refundRepository.findByIdempotencyKey("rfd-key-invalid")).thenReturn(Optional.empty());
        when(paymentTransactionRepository.findById(txId)).thenReturn(Optional.of(originalTx));

        assertThatThrownBy(() -> service.requestRefund(command))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed");
    }

    @Test
    @DisplayName("Admin phê duyệt và thực thi hoàn tiền tạo giao dịch REFUND trong sổ cái")
    void approveAndExecuteRefund_Success() {
        UUID refundId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID originalTxId = UUID.randomUUID();

        Refund refund = new Refund(
            refundId,
            "RFD-20261022-001",
            UUID.randomUUID(),
            originalTxId,
            new BigDecimal("300000.00"),
            "VND",
            "Hoàn tiền",
            adminId,
            "idemp-rfd-001"
        );

        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundResponse response = service.approveAndExecuteRefund(refundId, adminId);

        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(response.refundTransactionId()).isNotNull();

        verify(paymentTransactionRepository, times(1)).save(any(PaymentTransaction.class));
        verify(refundRepository, times(1)).save(refund);
    }
}
