package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.Payable;
import com.vistay.finance.domain.model.PayableStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayableRepository extends JpaRepository<Payable, UUID> {

    Optional<Payable> findByPayableCode(String payableCode);

    Optional<Payable> findBySourceKey(String sourceKey);

    List<Payable> findByPayeeId(UUID payeeId);

    List<Payable> findByStatus(PayableStatus status);

    List<Payable> findByDueDateBetween(LocalDate startDate, LocalDate endDate);
}

