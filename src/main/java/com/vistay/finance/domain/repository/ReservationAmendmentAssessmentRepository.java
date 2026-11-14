package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.ReservationAmendmentAssessment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationAmendmentAssessmentRepository extends JpaRepository<ReservationAmendmentAssessment, UUID> {

    Optional<ReservationAmendmentAssessment> findByIdempotencyKey(String idempotencyKey);

    Optional<ReservationAmendmentAssessment> findFirstByBookingIdAndProposalIdAndCurrentTrue(
        UUID bookingId,
        String proposalId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM ReservationAmendmentAssessment a
        WHERE a.bookingId = :bookingId
          AND a.proposalId = :proposalId
          AND a.current = true
        """)
    Optional<ReservationAmendmentAssessment> findCurrentForUpdate(
        @Param("bookingId") UUID bookingId,
        @Param("proposalId") String proposalId
    );

    Optional<ReservationAmendmentAssessment> findTopByBookingIdAndProposalIdOrderByAmendmentRevisionDesc(
        UUID bookingId,
        String proposalId
    );

    Optional<ReservationAmendmentAssessment> findByExecutedEventId(UUID executedEventId);

    List<ReservationAmendmentAssessment> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);
}
