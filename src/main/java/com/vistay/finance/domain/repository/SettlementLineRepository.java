package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.SettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SettlementLineRepository extends JpaRepository<SettlementLine, UUID> {
    List<SettlementLine> findBySettlementPeriodId(UUID settlementPeriodId);
    void deleteBySettlementPeriodId(UUID settlementPeriodId);
}

