package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.Refund;
import com.vistay.finance.domain.model.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByRefundCode(String refundCode);

    List<Refund> findByBookingIdOrderByRequestedAtDesc(UUID bookingId);

    List<Refund> findByStatus(RefundStatus status);
}
