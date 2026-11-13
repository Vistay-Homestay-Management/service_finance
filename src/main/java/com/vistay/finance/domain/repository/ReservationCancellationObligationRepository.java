package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.CancellationObligationStatus;
import com.vistay.finance.domain.model.ReservationCancellationObligation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationCancellationObligationRepository extends JpaRepository<ReservationCancellationObligation, UUID> {

    Optional<ReservationCancellationObligation> findByBookingId(UUID bookingId);

    Optional<ReservationCancellationObligation> findByCancellationQuoteId(UUID cancellationQuoteId);

    Optional<ReservationCancellationObligation> findBySourceEventId(UUID sourceEventId);

    Optional<ReservationCancellationObligation> findByRefundId(UUID refundId);

    List<ReservationCancellationObligation> findByStatus(CancellationObligationStatus status);

    List<ReservationCancellationObligation> findByPropertyId(UUID propertyId);
}
