package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.ReservationDepositAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationDepositAssessmentRepository extends JpaRepository<ReservationDepositAssessment, UUID> {

    Optional<ReservationDepositAssessment> findByTargetKeyAndIsCurrentTrue(String targetKey);

    Optional<ReservationDepositAssessment> findByIdempotencyKey(String idempotencyKey);

    Optional<ReservationDepositAssessment> findByBookingIdAndIsCurrentTrue(UUID bookingId);

    List<ReservationDepositAssessment> findByTargetKeyOrderByAssessmentRevisionDesc(String targetKey);

    List<ReservationDepositAssessment> findByPropertyIdOrderByCreatedAtDesc(UUID propertyId);

    Optional<ReservationDepositAssessment> findTopByTargetKeyOrderByAssessmentRevisionDesc(String targetKey);
}
