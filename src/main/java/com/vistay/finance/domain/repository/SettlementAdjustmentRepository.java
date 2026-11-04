package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.SettlementAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SettlementAdjustmentRepository extends JpaRepository<SettlementAdjustment, UUID> {
    List<SettlementAdjustment> findBySettlementPeriodId(UUID settlementPeriodId);
}

