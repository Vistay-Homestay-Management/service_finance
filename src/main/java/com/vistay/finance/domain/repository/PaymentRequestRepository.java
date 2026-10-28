package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.PaymentRequest;
import com.vistay.finance.domain.model.PaymentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {

    Optional<PaymentRequest> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentRequest> findByRequestCode(String requestCode);

    List<PaymentRequest> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    List<PaymentRequest> findByStatus(PaymentRequestStatus status);
}
