package com.gatekeeper.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.policy.dto.CreatePolicyDefinitionRequest;
import com.gatekeeper.policy.dto.CreatePolicyVersionRequest;
import com.gatekeeper.policy.dto.PolicyActivationAuditDto;
import com.gatekeeper.policy.dto.PolicyDefinitionDto;
import com.gatekeeper.policy.dto.PolicyVersionDto;
import com.gatekeeper.policy.entity.PolicyActivationAudit;
import com.gatekeeper.policy.entity.PolicyAuditAction;
import com.gatekeeper.policy.entity.PolicyDefinition;
import com.gatekeeper.policy.entity.PolicyStatus;
import com.gatekeeper.policy.entity.PolicyVersion;
import com.gatekeeper.policy.engine.DefaultPolicyEvaluationEngine;
import com.gatekeeper.policy.engine.PolicyCacheManager;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.model.PolicyRuleConditions;
import com.gatekeeper.policy.repository.PolicyActivationAuditRepository;
import com.gatekeeper.policy.repository.PolicyDefinitionRepository;
import com.gatekeeper.policy.repository.PolicyVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Profile("!foundation")
public class PolicyAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyAdministrationService.class);

    private final PolicyDefinitionRepository definitionRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicyActivationAuditRepository auditRepository;
    private final PolicyCacheManager cacheManager;
    private final ObjectMapper objectMapper;

    public PolicyAdministrationService(
            PolicyDefinitionRepository definitionRepository,
            PolicyVersionRepository versionRepository,
            PolicyActivationAuditRepository auditRepository,
            PolicyCacheManager cacheManager,
            ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.auditRepository = auditRepository;
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PolicyDefinitionDto createDefinition(CreatePolicyDefinitionRequest req) {
        String key = req.policyKey().trim();
        if (definitionRepository.existsByPolicyKey(key)) {
            throw new IllegalArgumentException("Policy with key '" + key + "' already exists");
        }

        PolicyDefinition def = new PolicyDefinition(key, req.name().trim(), req.description());
        PolicyDefinition saved = definitionRepository.save(def);
        log.info("Created policy definition: key='{}', id={}", key, saved.getId());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<PolicyDefinitionDto> listDefinitions() {
        return definitionRepository.findAll().stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PolicyDefinitionDto getDefinition(String policyKey) {
        PolicyDefinition def = findDefinitionOrThrow(policyKey);
        return toDto(def);
    }

    @Transactional
    public PolicyVersionDto createVersion(String policyKey, CreatePolicyVersionRequest req, String actor) {
        PolicyDefinition def = findDefinitionOrThrow(policyKey);
        List<PolicyRule> rules = req.rules();

        validateRulesStrictly(rules);

        String rulesJson;
        try {
            rulesJson = objectMapper.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize policy rules to JSON: " + e.getMessage());
        }

        int nextVersion = versionRepository.findMaxVersionNumberByDefinitionId(def.getId()) + 1;
        PolicyVersion version = new PolicyVersion(def, nextVersion, rulesJson, req.description(), actor);
        PolicyVersion saved = versionRepository.save(version);

        log.info("Created immutable policy version: key='{}', version={}, rulesCount={}, actor='{}'",
            policyKey, nextVersion, rules.size(), actor);

        return toVersionDto(saved, rules);
    }

    @Transactional(readOnly = true)
    public List<PolicyVersionDto> listVersions(String policyKey) {
        findDefinitionOrThrow(policyKey);
        return versionRepository.findByPolicyDefinitionPolicyKeyOrderByVersionNumberDesc(policyKey).stream()
            .map(v -> toVersionDto(v, parseRules(v.getRulesJson())))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PolicyVersionDto getVersion(String policyKey, int versionNumber) {
        PolicyVersion version = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, versionNumber)
            .orElseThrow(() -> new ResourceNotFoundException("Policy version not found: key=" + policyKey + ", version=" + versionNumber));
        return toVersionDto(version, parseRules(version.getRulesJson()));
    }

    @Transactional
    public PolicyDefinitionDto activateVersion(String policyKey, int versionNumber, String actor, String reason) {
        PolicyDefinition def = findDefinitionOrThrow(policyKey);
        PolicyVersion targetVersion = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, versionNumber)
            .orElseThrow(() -> new ResourceNotFoundException("Policy version " + versionNumber + " does not exist for policy " + policyKey));

        PolicyVersion currentActive = def.getActiveVersion();
        Integer previousVerNumber = currentActive != null ? currentActive.getVersionNumber() : null;

        if (currentActive != null && currentActive.getId().equals(targetVersion.getId())) {
            log.info("Policy version {} is already active for policy '{}'", versionNumber, policyKey);
            return toDto(def);
        }

        if (currentActive != null) {
            currentActive.setStatus(PolicyStatus.RETIRED);
            versionRepository.save(currentActive);
        }

        targetVersion.setStatus(PolicyStatus.ACTIVE);
        targetVersion.setActivatedAt(java.time.Instant.now());
        targetVersion.setActivatedBy(actor);
        versionRepository.save(targetVersion);

        def.setActiveVersion(targetVersion);
        PolicyDefinition saved = definitionRepository.save(def);

        PolicyActivationAudit audit = new PolicyActivationAudit(
            def,
            versionNumber,
            previousVerNumber,
            PolicyAuditAction.ACTIVATED,
            actor,
            reason != null ? reason : "Activated policy version " + versionNumber
        );
        auditRepository.save(audit);

        // Atomic cache refresh & multi-instance notification
        cacheManager.invalidateAndBroadcast(policyKey);

        log.info("Policy '{}' successfully activated version {}. Previous version: {}. Actor: '{}'",
            policyKey, versionNumber, previousVerNumber, actor);

        return toDto(saved);
    }

    @Transactional
    public PolicyDefinitionDto rollback(String policyKey, Integer targetVersionNumber, String actor, String reason) {
        PolicyDefinition def = findDefinitionOrThrow(policyKey);
        PolicyVersion currentActive = def.getActiveVersion();
        if (currentActive == null) {
            throw new IllegalStateException("Cannot rollback policy '" + policyKey + "' because no version is currently active");
        }

        PolicyVersion targetVersion;
        if (targetVersionNumber != null) {
            targetVersion = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, targetVersionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Target rollback version " + targetVersionNumber + " does not exist"));
        } else {
            // Find previously active version from audit history
            List<PolicyActivationAudit> audits = auditRepository.findByPolicyDefinitionPolicyKeyOrderByCreatedAtDesc(policyKey);
            Integer candidate = null;
            for (PolicyActivationAudit a : audits) {
                if (a.getPreviousVersionNumber() != null && a.getPreviousVersionNumber() != currentActive.getVersionNumber()) {
                    candidate = a.getPreviousVersionNumber();
                    break;
                }
            }
            if (candidate == null) {
                // Default to version N-1 if exists
                candidate = currentActive.getVersionNumber() - 1;
            }

            final int verToFind = candidate;
            targetVersion = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, verToFind)
                .orElseThrow(() -> new IllegalStateException("No valid previous version found to roll back policy '" + policyKey + "'"));
        }

        currentActive.setStatus(PolicyStatus.ROLLED_BACK);
        versionRepository.save(currentActive);

        targetVersion.setStatus(PolicyStatus.ACTIVE);
        targetVersion.setActivatedAt(java.time.Instant.now());
        targetVersion.setActivatedBy(actor);
        versionRepository.save(targetVersion);

        def.setActiveVersion(targetVersion);
        PolicyDefinition saved = definitionRepository.save(def);

        PolicyActivationAudit audit = new PolicyActivationAudit(
            def,
            targetVersion.getVersionNumber(),
            currentActive.getVersionNumber(),
            PolicyAuditAction.ROLLED_BACK,
            actor,
            reason != null ? reason : "Rolled back to version " + targetVersion.getVersionNumber()
        );
        auditRepository.save(audit);

        // Atomic cache refresh & multi-instance notification
        cacheManager.invalidateAndBroadcast(policyKey);

        log.info("Policy '{}' successfully rolled back from version {} to {}. Actor: '{}'",
            policyKey, currentActive.getVersionNumber(), targetVersion.getVersionNumber(), actor);

        return toDto(saved);
    }

    @Transactional
    public PolicyDefinitionDto configureShadowVersion(String policyKey, Integer shadowVersionNumber, String actor) {
        PolicyDefinition def = findDefinitionOrThrow(policyKey);

        PolicyVersion shadowVersion = null;
        if (shadowVersionNumber != null) {
            shadowVersion = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, shadowVersionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Shadow version " + shadowVersionNumber + " does not exist for policy " + policyKey));
        }

        def.setShadowVersion(shadowVersion);
        PolicyDefinition saved = definitionRepository.save(def);

        PolicyActivationAudit audit = new PolicyActivationAudit(
            def,
            shadowVersionNumber != null ? shadowVersionNumber : 0,
            def.getActiveVersion() != null ? def.getActiveVersion().getVersionNumber() : null,
            shadowVersionNumber != null ? PolicyAuditAction.SHADOW_SET : PolicyAuditAction.SHADOW_CLEARED,
            actor,
            shadowVersionNumber != null ? "Set shadow version to " + shadowVersionNumber : "Cleared shadow version"
        );
        auditRepository.save(audit);

        cacheManager.invalidateAndBroadcast(policyKey);

        log.info("Configured shadow policy for '{}': version={}, actor='{}'",
            policyKey, shadowVersionNumber, actor);

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<PolicyActivationAuditDto> getAuditHistory(String policyKey) {
        findDefinitionOrThrow(policyKey);
        return auditRepository.findByPolicyDefinitionPolicyKeyOrderByCreatedAtDesc(policyKey).stream()
            .map(this::toAuditDto)
            .collect(Collectors.toList());
    }

    public List<PolicyRule> parseRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(rulesJson, new TypeReference<List<PolicyRule>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse rulesJson: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void validateRulesStrictly(List<PolicyRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Policy rules list must not be empty");
        }
        if (rules.size() > DefaultPolicyEvaluationEngine.MAX_RULES_PER_POLICY) {
            throw new IllegalArgumentException("Policy exceeds maximum allowed rules (" + DefaultPolicyEvaluationEngine.MAX_RULES_PER_POLICY + ")");
        }

        Set<String> ruleIds = new HashSet<>();
        for (PolicyRule r : rules) {
            if (r.ruleId() == null || !r.ruleId().matches("^[a-zA-Z0-9_-]{3,64}$")) {
                throw new IllegalArgumentException("Invalid ruleId '" + r.ruleId() + "': must be 3-64 alphanumeric characters, underscores or hyphens");
            }
            if (!ruleIds.add(r.ruleId())) {
                throw new IllegalArgumentException("Duplicate ruleId found in policy: '" + r.ruleId() + "'");
            }
            if (r.priority() <= 0 || r.priority() > 100_000) {
                throw new IllegalArgumentException("Invalid priority " + r.priority() + " for rule '" + r.ruleId() + "': must be between 1 and 100000");
            }
            if (r.action() == null) {
                throw new IllegalArgumentException("Rule '" + r.ruleId() + "' must declare an action (ALLOW_CONTINUE or DENY)");
            }

            PolicyRuleConditions c = r.conditions();
            if (c != null) {
                if (c.timeWindowStartUtc() != null || c.timeWindowEndUtc() != null) {
                    if (c.timeWindowStartUtc() == null || c.timeWindowEndUtc() == null) {
                        throw new IllegalArgumentException("Rule '" + r.ruleId() + "': both timeWindowStartUtc and timeWindowEndUtc must be provided for time windows");
                    }
                    try {
                        LocalTime.parse(c.timeWindowStartUtc());
                        LocalTime.parse(c.timeWindowEndUtc());
                    } catch (DateTimeParseException ex) {
                        throw new IllegalArgumentException("Rule '" + r.ruleId() + "': malformed time window format (expected HH:mm or HH:mm:ss): " + ex.getMessage());
                    }
                }
            }
        }
    }

    private PolicyDefinition findDefinitionOrThrow(String policyKey) {
        return definitionRepository.findByPolicyKey(policyKey)
            .orElseThrow(() -> new ResourceNotFoundException("Policy definition not found with key: " + policyKey));
    }

    private PolicyDefinitionDto toDto(PolicyDefinition def) {
        return new PolicyDefinitionDto(
            def.getId(),
            def.getPolicyKey(),
            def.getName(),
            def.getDescription(),
            def.getActiveVersion() != null ? def.getActiveVersion().getVersionNumber() : null,
            def.getShadowVersion() != null ? def.getShadowVersion().getVersionNumber() : null,
            def.getVersionLock(),
            def.getCreatedAt(),
            def.getUpdatedAt()
        );
    }

    private PolicyVersionDto toVersionDto(PolicyVersion ver, List<PolicyRule> rules) {
        return new PolicyVersionDto(
            ver.getId(),
            ver.getPolicyDefinition().getPolicyKey(),
            ver.getVersionNumber(),
            ver.getStatus().name(),
            ver.getDescription(),
            rules,
            rules.size(),
            ver.getCreatedAt(),
            ver.getCreatedBy(),
            ver.getActivatedAt(),
            ver.getActivatedBy()
        );
    }

    private PolicyActivationAuditDto toAuditDto(PolicyActivationAudit a) {
        return new PolicyActivationAuditDto(
            a.getId(),
            a.getPolicyDefinition().getPolicyKey(),
            a.getVersionNumber(),
            a.getPreviousVersionNumber(),
            a.getAction().name(),
            a.getActor(),
            a.getReason(),
            a.getCreatedAt()
        );
    }
}
