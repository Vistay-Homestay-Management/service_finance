package com.vistay.finance.application.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistay.finance.domain.model.RuleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;

@Component
public class RuleParameterValidator {

    private static final int MAX_PARAM_BYTES = 65536; // 64KB max

    private static final Set<String> ALLOWED_PRICING_FIELDS = Set.of(
            "currency", "weekendMultiplier", "holidayMultiplier", "seasonalMultiplier", "baseRate"
    );
    private static final Set<String> ALLOWED_CANCELLATION_FIELDS = Set.of(
            "freeCancelHours", "penaltyPercentage", "penaltyNights", "noShowCutoffHour"
    );
    private static final Set<String> ALLOWED_DEPOSIT_FIELDS = Set.of(
            "depositRate", "fixedDepositAmount", "holdMinutes", "currency"
    );
    private static final Set<String> ALLOWED_REFUND_FIELDS = Set.of(
            "refundRate", "processingFee", "maxRefundAmount", "currency"
    );
    private static final Set<String> ALLOWED_SETTLEMENT_FIELDS = Set.of(
            "commissionRate", "cleaningFeePayoutRate", "settlementCycleDays", "holdingDays", "minPayoutAmount", "currency"
    );

    private final ObjectMapper objectMapper;

    public RuleParameterValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String validateAndNormalize(RuleType ruleType, String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return "{}";
        }

        if (parametersJson.getBytes().length > MAX_PARAM_BYTES) {
            throw new IllegalArgumentException("Parameters payload exceeds maximum allowed size of 64KB");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(parametersJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON parameters: " + e.getMessage(), e);
        }

        if (!root.isObject()) {
            throw new IllegalArgumentException("Parameters must be a valid JSON object, array or primitive is not allowed");
        }

        validateRuleSpecificParameters(ruleType, root);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return parametersJson.trim();
        }
    }

    private void validateRuleSpecificParameters(RuleType ruleType, JsonNode root) {
        if (ruleType == null || root.isEmpty()) {
            return;
        }

        Set<String> allowedFields = switch (ruleType) {
            case PRICING -> ALLOWED_PRICING_FIELDS;
            case CANCELLATION -> ALLOWED_CANCELLATION_FIELDS;
            case DEPOSIT -> ALLOWED_DEPOSIT_FIELDS;
            case REFUND -> ALLOWED_REFUND_FIELDS;
            case SETTLEMENT -> ALLOWED_SETTLEMENT_FIELDS;
        };

        // 1. Strict unknown field rejection
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unknown parameter '" + field + "' is not permitted for rule type " + ruleType);
            }
        }

        // 2. Value validation per rule type
        switch (ruleType) {
            case PRICING -> {
                validatePositiveDouble(root, "weekendMultiplier");
                validatePositiveDouble(root, "holidayMultiplier");
                validatePositiveDouble(root, "seasonalMultiplier");
                validateNonNegativeDecimal(root, "baseRate");
                validateCurrency(root, "currency");
            }
            case CANCELLATION -> {
                validateNonNegativeInt(root, "freeCancelHours");
                validateNonNegativeInt(root, "penaltyNights");
                validateHourOfDay(root, "noShowCutoffHour");
                validatePercentageRate(root, "penaltyPercentage", 100.0);
            }
            case DEPOSIT -> {
                validatePercentageRate(root, "depositRate", 1.0);
                validateNonNegativeDecimal(root, "fixedDepositAmount");
                validatePositiveInt(root, "holdMinutes");
                validateCurrency(root, "currency");
            }
            case REFUND -> {
                validatePercentageRate(root, "refundRate", 1.0);
                validateNonNegativeDecimal(root, "processingFee");
                validateNonNegativeDecimal(root, "maxRefundAmount");
                validateCurrency(root, "currency");
            }
            case SETTLEMENT -> {
                validatePercentageRate(root, "commissionRate", 1.0);
                validatePercentageRate(root, "cleaningFeePayoutRate", 1.0);
                validatePositiveInt(root, "settlementCycleDays");
                validateNonNegativeInt(root, "holdingDays");
                validateNonNegativeDecimal(root, "minPayoutAmount");
                validateCurrency(root, "currency");
            }
        }
    }

    private void validatePositiveDouble(JsonNode root, String field) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isNumber() || node.asDouble() <= 0.0) {
                throw new IllegalArgumentException("Field '" + field + "' must be a positive number greater than 0");
            }
        }
    }

    private void validateNonNegativeInt(JsonNode root, String field) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isInt() || node.asInt() < 0) {
                throw new IllegalArgumentException("Field '" + field + "' must be a non-negative integer (>= 0)");
            }
        }
    }

    private void validatePositiveInt(JsonNode root, String field) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isInt() || node.asInt() < 1) {
                throw new IllegalArgumentException("Field '" + field + "' must be a positive integer (>= 1)");
            }
        }
    }

    private void validateHourOfDay(JsonNode root, String field) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isInt() || node.asInt() < 0 || node.asInt() > 23) {
                throw new IllegalArgumentException("Field '" + field + "' must be a valid hour of day between 0 and 23");
            }
        }
    }

    private void validatePercentageRate(JsonNode root, String field, double maxAllowed) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isNumber()) {
                throw new IllegalArgumentException("Field '" + field + "' must be a numeric percentage/rate");
            }
            double val = node.asDouble();
            if (val < 0.0 || val > maxAllowed) {
                throw new IllegalArgumentException("Field '" + field + "' must be between 0.0 and " + maxAllowed);
            }
        }
    }

    private void validateNonNegativeDecimal(JsonNode root, String field) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isNumber()) {
                throw new IllegalArgumentException("Field '" + field + "' must be a numeric amount");
            }
            BigDecimal bd = new BigDecimal(node.asText());
            if (bd.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Field '" + field + "' cannot be negative");
            }
        }
    }

    private void validateCurrency(JsonNode root, String field) {
        if (root.has(field)) {
            JsonNode node = root.get(field);
            if (!node.isTextual() || !node.asText().matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException("Field '" + field + "' must be a 3-letter uppercase ISO currency code (e.g., 'VND', 'USD')");
            }
        }
    }
}
