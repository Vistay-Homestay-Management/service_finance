package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.RuleCatalogue;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class RuleCatalogueService {

    private final RuleCatalogueRepository ruleRepository;

    public RuleCatalogueService(RuleCatalogueRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    public RuleCatalogueResponse createRule(CreateRuleCatalogueCommand cmd, String actorId) {
        if (ruleRepository.existsByRuleCode(cmd.ruleCode())) {
            throw new IllegalArgumentException("Rule code already exists: " + cmd.ruleCode());
        }

        RuleCatalogue rule = new RuleCatalogue(
                cmd.ruleCode(),
                cmd.ruleName(),
                cmd.ruleType(),
                cmd.description(),
                cmd.parametersJson(),
                cmd.effectiveFrom(),
                cmd.effectiveTo(),
                actorId
        );

        RuleCatalogue saved = ruleRepository.save(rule);
        return RuleCatalogueResponse.from(saved);
    }

    public RuleCatalogueResponse submitForApproval(UUID id) {
        RuleCatalogue rule = findRuleOrThrow(id);
        rule.submitForApproval();
        return RuleCatalogueResponse.from(ruleRepository.save(rule));
    }

    public RuleCatalogueResponse approveRule(UUID id, String approverActorId) {
        RuleCatalogue rule = findRuleOrThrow(id);
        rule.approve(approverActorId);
        return RuleCatalogueResponse.from(ruleRepository.save(rule));
    }

    public RuleCatalogueResponse rejectRule(UUID id, String reviewerActorId, String reason) {
        RuleCatalogue rule = findRuleOrThrow(id);
        rule.reject(reviewerActorId, reason);
        return RuleCatalogueResponse.from(ruleRepository.save(rule));
    }

    public RuleCatalogueResponse archiveRule(UUID id) {
        RuleCatalogue rule = findRuleOrThrow(id);
        rule.archive();
        return RuleCatalogueResponse.from(ruleRepository.save(rule));
    }

    @Transactional(readOnly = true)
    public RuleCatalogueResponse getRuleById(UUID id) {
        return RuleCatalogueResponse.from(findRuleOrThrow(id));
    }

    @Transactional(readOnly = true)
    public RuleCatalogueResponse getRuleByCode(String code) {
        return ruleRepository.findByRuleCode(code)
                .map(RuleCatalogueResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Rule catalogue not found for code: " + code));
    }

    @Transactional(readOnly = true)
    public List<RuleCatalogueResponse> getRules(RuleType type, RuleStatus status) {
        List<RuleCatalogue> list;
        if (type != null && status != null) {
            list = ruleRepository.findByRuleTypeAndStatus(type, status);
        } else if (type != null) {
            list = ruleRepository.findByRuleType(type);
        } else if (status != null) {
            list = ruleRepository.findByStatus(status);
        } else {
            list = ruleRepository.findAll();
        }
        return list.stream().map(RuleCatalogueResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<RuleCatalogueResponse> getActiveEffectiveRule(RuleType type, Instant at) {
        Instant checkTime = (at != null) ? at : Instant.now();
        List<RuleCatalogue> activeRules = ruleRepository.findActiveEffectiveRules(type, checkTime);
        if (activeRules.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RuleCatalogueResponse.from(activeRules.get(0)));
    }

    private RuleCatalogue findRuleOrThrow(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Rule catalogue not found with id: " + id));
    }
}

