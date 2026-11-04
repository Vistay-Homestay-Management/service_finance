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

    List<RuleCatalogue> findByRuleCodeOrderByRuleVersionDesc(String ruleCode);

    Optional<RuleCatalogue> findByRuleCodeAndRuleVersion(String ruleCode, int ruleVersion);

    Optional<RuleCatalogue> findTopByRuleCodeOrderByRuleVersionDesc(String ruleCode);

    boolean existsByRuleCode(String ruleCode);

    boolean existsByRuleCodeAndRuleVersion(String ruleCode, int ruleVersion);

    List<RuleCatalogue> findByRuleType(RuleType ruleType);

    List<RuleCatalogue> findByRuleTypeAndStatus(RuleType ruleType, RuleStatus status);

    List<RuleCatalogue> findByStatus(RuleStatus status);

    @Query("SELECT r FROM RuleCatalogue r " +
           "WHERE r.ruleCode = :ruleCode " +
           "AND r.ruleType = :ruleType " +
           "AND r.status = 'ACTIVE' " +
           "AND r.effectiveFrom <= :at " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo > :at) " +
           "ORDER BY r.ruleVersion DESC")
    List<RuleCatalogue> findActiveEffectiveRulesByTypeAndCode(@Param("ruleType") RuleType ruleType,
                                                              @Param("ruleCode") String ruleCode,
                                                              @Param("at") Instant at);

    @Query("SELECT r FROM RuleCatalogue r " +
           "WHERE r.ruleCode = :ruleCode " +
           "AND r.status = 'ACTIVE' " +
           "AND r.effectiveFrom <= :at " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo > :at) " +
           "ORDER BY r.ruleVersion DESC")
    List<RuleCatalogue> findActiveEffectiveRulesByCode(@Param("ruleCode") String ruleCode,
                                                      @Param("at") Instant at);

    @Query("SELECT r FROM RuleCatalogue r " +
           "WHERE r.ruleType = :ruleType " +
           "AND r.status = 'ACTIVE' " +
           "AND r.effectiveFrom <= :at " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo > :at) " +
           "ORDER BY r.ruleCode ASC, r.ruleVersion DESC")
    List<RuleCatalogue> findActiveEffectiveRulesByType(@Param("ruleType") RuleType ruleType,
                                                      @Param("at") Instant at);
}
