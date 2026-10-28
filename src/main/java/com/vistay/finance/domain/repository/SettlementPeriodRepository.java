package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.SettlementPeriod;
import com.vistay.finance.domain.model.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementPeriodRepository extends JpaRepository<SettlementPeriod, UUID> {
    Optional<SettlementPeriod> findByPeriodCode(String periodCode);
    List<SettlementPeriod> findByHostId(UUID hostId);
    List<SettlementPeriod> findByStatus(SettlementStatus status);
}

