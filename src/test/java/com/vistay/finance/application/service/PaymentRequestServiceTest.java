package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.CreatePaymentRequestCommand;
import com.vistay.finance.application.dto.PaymentRequestResponse;
import com.vistay.finance.domain.model.PayerType;
import com.vistay.finance.domain.model.PaymentRequest;
import com.vistay.finance.domain.model.PaymentRequestStatus;
import com.vistay.finance.domain.repository.PaymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    private PaymentRequestService service;

    @BeforeEach
    void setUp() {
        service = new PaymentRequestService(paymentRequestRepository);
    }

    @Test
    @DisplayName("Tạo yêu cầu thanh toán thành công khi chưa trùng idempotency key")
    void createPaymentRequest_Success() {
        String idempotencyKey = "key-test-123";
        CreatePaymentRequestCommand command = new CreatePaymentRequestCommand(
            UUID.randomUUID(),
            PayerType.CUSTOMER,
            UUID.randomUUID(),
            new BigDecimal("1500000.00"),
            "VND",
            "Thanh toán đặt cọc phòng 301",
            idempotencyKey,
            Instant.now().plusSeconds(3600),
            null
        );

        when(paymentRequestRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentRequestResponse response = service.createPaymentRequest(command);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("1500000.00"));
        assertThat(response.status()).isEqualTo(PaymentRequestStatus.PENDING);
        assertThat(response.requestCode()).startsWith("PAY-REQ-");
        assertThat(response.idempotencyKey()).isEqualTo(idempotencyKey);

        verify(paymentRequestRepository, times(1)).save(any(PaymentRequest.class));
    }

    @Test
    @DisplayName("Đảm bảo Idempotency: Không tạo trùng khi idempotency key đã tồn tại")
    void createPaymentRequest_Idempotent() {
        String idempotencyKey = "existing-key-456";
        PaymentRequest existing = new PaymentRequest(
            UUID.randomUUID(),
            "PAY-REQ-20261022-EXISTING",
            UUID.randomUUID(),
            PayerType.CUSTOMER,
            UUID.randomUUID(),
            new BigDecimal("2000000.00"),
            "VND",
            "Thanh toán cọc",
            idempotencyKey,
            null,
            null
        );

        when(paymentRequestRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        CreatePaymentRequestCommand command = new CreatePaymentRequestCommand(
            existing.getBookingId(),
            PayerType.CUSTOMER,
            existing.getPayerId(),
            new BigDecimal("2000000.00"),
            "VND",
            "Thanh toán cọc",
            idempotencyKey,
            null,
            null
        );

        PaymentRequestResponse response = service.createPaymentRequest(command);

        assertThat(response).isNotNull();
        assertThat(response.requestCode()).isEqualTo("PAY-REQ-20261022-EXISTING");
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("2000000.00"));

        verify(paymentRequestRepository, never()).save(any(PaymentRequest.class));
    }

    @Test
    @DisplayName("Hủy yêu cầu thanh toán chuyển status sang CANCELLED")
    void cancelPaymentRequest_Success() {
        UUID id = UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(
            id,
            "PAY-REQ-123",
            UUID.randomUUID(),
            PayerType.CUSTOMER,
            UUID.randomUUID(),
            new BigDecimal("500000.00"),
            "VND",
            "Thanh toán cọc",
            "key-123",
            null,
            null
        );

        when(paymentRequestRepository.findById(id)).thenReturn(Optional.of(request));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentRequestResponse response = service.cancelPaymentRequest(id);

        assertThat(response.status()).isEqualTo(PaymentRequestStatus.CANCELLED);
        verify(paymentRequestRepository, times(1)).save(request);
    }
}
