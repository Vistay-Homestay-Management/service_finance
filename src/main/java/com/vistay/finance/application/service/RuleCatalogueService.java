package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.validation.RuleParameterValidator;
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
    private final RuleParameterValidator parameterValidator;

    public RuleCatalogueService(RuleCatalogueRepository ruleRepository,
                                RuleParameterValidator parameterValidator) {
        this.ruleRepository = ruleRepository;
        this.parameterValidator = parameterValidator;
    }

    public RuleCatalogueResponse createRule(CreateRuleCatalogueCommand cmd, String actorId) {
        if (ruleRepository.existsByRuleCodeAndRuleVersion(cmd.ruleCode(), 1)) {
            throw new IllegalArgumentException("Rule code already exists at version 1: " + cmd.ruleCode()
                    + ". Use createNewVersion endpoint to publish subsequent versions.");
        }

        String normalizedParams = parameterValidator.validateAndNormalize(cmd.ruleType(), cmd.parametersJson());

        RuleCatalogue rule = new RuleCatalogue(
                cmd.ruleCode(),
                cmd.ruleName(),
                cmd.ruleType(),
                1,
                cmd.description(),
                normalizedParams,
                cmd.effectiveFrom(),
                cmd.effectiveTo(),
                actorId
        );

        RuleCatalogue saved = ruleRepository.save(rule);
        return RuleCatalogueResponse.from(saved);
    }

    public RuleCatalogueResponse createNewVersion(String ruleCode, CreateRuleVersionCommand cmd, String actorId) {
        RuleCatalogue previous = ruleRepository.findTopByRuleCodeOrderByRuleVersionDesc(ruleCode)
                .orElseThrow(() -> new NoSuchElementException("Cannot create new version: logical rule not found for code: " + ruleCode));

        int nextVersion = previous.getRuleVersion() + 1;
        String normalizedParams = parameterValidator.validateAndNormalize(previous.getRuleType(), cmd.parametersJson());

        RuleCatalogue newVersion = new RuleCatalogue(
                previous.getRuleCode(),
                cmd.ruleName(),
                previous.getRuleType(),
                nextVersion,
                cmd.description(),
                normalizedParams,
                cmd.effectiveFrom(),
                cmd.effectiveTo(),
                actorId
        );

        RuleCatalogue saved = ruleRepository.save(newVersion);
        return RuleCatalogueResponse.from(saved);
    }

    public RuleCatalogueResponse submitForApproval(UUID id) {
        RuleCatalogue rule = findRuleOrThrow(id);
        rule.submitForApproval();
        return RuleCatalogueResponse.from(ruleRepository.save(rule));
    }

    public RuleCatalogueResponse approveRule(UUID id, String approverActorId) {
        RuleCatalogue rule = findRuleOrThrow(id);

        // Check for conflicting overlapping ACTIVE rules on the same rule_code
        List<RuleCatalogue> activeRules = ruleRepository.findActiveEffectiveRulesByCode(rule.getRuleCode(), rule.getEffectiveFrom());
        for (RuleCatalogue active : activeRules) {
            if (!active.getId().equals(rule.getId())) {
                boolean overlaps = (active.getEffectiveTo() == null || active.getEffectiveTo().isAfter(rule.getEffectiveFrom()))
                        && (rule.getEffectiveTo() == null || rule.getEffectiveTo().isAfter(active.getEffectiveFrom()));
                if (overlaps) {
                    throw new IllegalStateException("Cannot activate rule: Overlapping ACTIVE rule version ("
                            + active.getRuleVersion() + ") already exists for code '" + rule.getRuleCode()
                            + "' within the specified effective window.");
                }
            }
        }

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
    public RuleCatalogueResponse getRuleByCodeAndVersion(String code, int version) {
        return ruleRepository.findByRuleCodeAndRuleVersion(code, version)
                .map(RuleCatalogueResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Rule catalogue not found for code: " + code + " version: " + version));
    }

    @Transactional(readOnly = true)
    public List<RuleCatalogueResponse> getRuleVersionsByCode(String code) {
        return ruleRepository.findByRuleCodeOrderByRuleVersionDesc(code)
                .stream()
                .map(RuleCatalogueResponse::from)
                .toList();
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
    public Optional<RuleCatalogueResponse> getActiveEffectiveRule(RuleType type, String ruleCode, Instant at) {
        Instant checkTime = (at != null) ? at : Instant.now();

        if (ruleCode != null && !ruleCode.isBlank()) {
            List<RuleCatalogue> activeByCode = ruleRepository.findActiveEffectiveRulesByCode(ruleCode.trim(), checkTime);
            if (activeByCode.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(RuleCatalogueResponse.from(activeByCode.get(0)));
        }

        List<RuleCatalogue> activeByType = ruleRepository.findActiveEffectiveRulesByType(type, checkTime);
        if (activeByType.isEmpty()) {
            return Optional.empty();
        }
        if (activeByType.size() > 1) {
            throw new IllegalStateException("Ambiguous active rules: Found " + activeByType.size()
                    + " active rules for type " + type + ". Specify 'code' parameter to select unambiguous rule.");
        }
        return Optional.of(RuleCatalogueResponse.from(activeByType.get(0)));
    }

    @Transactional(readOnly = true)
    public List<RuleCatalogueResponse> getActiveEffectiveRules(RuleType type, Instant at) {
        Instant checkTime = (at != null) ? at : Instant.now();
        return ruleRepository.findActiveEffectiveRulesByType(type, checkTime)
                .stream()
                .map(RuleCatalogueResponse::from)
                .toList();
    }

    private RuleCatalogue findRuleOrThrow(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Rule catalogue not found with id: " + id));
    }
}
