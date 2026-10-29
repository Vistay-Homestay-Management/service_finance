package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.RuleCatalogue;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RuleCatalogueRepository extends JpaRepository<RuleCatalogue, UUID> {

    Optional<RuleCatalogue> findByRuleCode(String ruleCode);

    boolean existsByRuleCode(String ruleCode);

    List<RuleCatalogue> findByRuleType(RuleType ruleType);

    List<RuleCatalogue> findByRuleTypeAndStatus(RuleType ruleType, RuleStatus status);

    List<RuleCatalogue> findByStatus(RuleStatus status);

    @Query("SELECT r FROM RuleCatalogue r " +
           "WHERE r.ruleType = :ruleType " +
           "AND r.status = 'ACTIVE' " +
           "AND r.effectiveFrom <= :at " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :at) " +
           "ORDER BY r.ruleVersion DESC, r.effectiveFrom DESC")
    List<RuleCatalogue> findActiveEffectiveRules(@Param("ruleType") RuleType ruleType,
                                                @Param("at") Instant at);
}

