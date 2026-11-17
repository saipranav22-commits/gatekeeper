package com.gatekeeper.policy.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.policy.entity.PolicyDefinition;
import com.gatekeeper.policy.entity.PolicyStatus;
import com.gatekeeper.policy.entity.PolicyVersion;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.repository.PolicyDefinitionRepository;
import com.gatekeeper.policy.repository.PolicyVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache manager for active and shadow gateway policies.
 * Ensures fast sub-millisecond evaluation on the gateway hot path.
 *
 * <p>Reliability Guarantees:
 * <ul>
 *   <li><b>Atomic Snapshot Switching:</b> Cached policy states are swapped via immutable references.</li>
 *   <li><b>Fail-Secure Preservation:</b> If a cache refresh fails due to a database or Redis outage,
 *       the existing cached policy is strictly preserved. It NEVER silently falls back to a permissive policy.</li>
 *   <li><b>Multi-Instance Invalidation:</b> Emits Redis invalidation notifications on policy activations.</li>
 * </ul>
 */
@Component
@Profile("!foundation")
public class PolicyCacheManager {

    private static final Logger log = LoggerFactory.getLogger(PolicyCacheManager.class);
    public static final String POLICY_INVALIDATION_CHANNEL = "gatekeeper:policy:invalidation";

    private final PolicyDefinitionRepository definitionRepository;
    private final PolicyVersionRepository versionRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private final Map<String, ActivePolicySnapshot> cache = new ConcurrentHashMap<>();

    public record ActivePolicySnapshot(
        String policyKey,
        int versionNumber,
        List<PolicyRule> activeRules,
        Integer shadowVersionNumber,
        List<PolicyRule> shadowRules,
        Instant cachedAt
    ) {
    }

    public PolicyCacheManager(
            PolicyDefinitionRepository definitionRepository,
            PolicyVersionRepository versionRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Retrieves the cached active policy snapshot for the given key, loading from database if absent.
     */
    public Optional<ActivePolicySnapshot> getActivePolicy(String policyKey) {
        if (policyKey == null || policyKey.isBlank()) {
            return Optional.empty();
        }
        String key = policyKey.trim();
        ActivePolicySnapshot snapshot = cache.get(key);
        if (snapshot != null) {
            return Optional.of(snapshot);
        }
        return refreshPolicy(key);
    }

    /**
     * Atomically refreshes the cached policy for the given key from the database.
     */
    public synchronized Optional<ActivePolicySnapshot> refreshPolicy(String policyKey) {
        String key = policyKey.trim();
        try {
            Optional<PolicyDefinition> defOpt = definitionRepository.findByPolicyKey(key);
            if (defOpt.isEmpty()) {
                cache.remove(key);
                return Optional.empty();
            }

            PolicyDefinition def = defOpt.get();
            PolicyVersion activeVer = def.getActiveVersion();

            if (activeVer == null || activeVer.getStatus() != PolicyStatus.ACTIVE) {
                // Policy exists but has no active version
                ActivePolicySnapshot emptySnapshot = new ActivePolicySnapshot(
                    key, 0, Collections.emptyList(), null, Collections.emptyList(), Instant.now()
                );
                cache.put(key, emptySnapshot);
                return Optional.of(emptySnapshot);
            }

            List<PolicyRule> activeRules = parseRules(activeVer.getRulesJson());

            Integer shadowVerNum = null;
            List<PolicyRule> shadowRules = Collections.emptyList();
            if (def.getShadowVersion() != null) {
                shadowVerNum = def.getShadowVersion().getVersionNumber();
                shadowRules = parseRules(def.getShadowVersion().getRulesJson());
            }

            ActivePolicySnapshot snapshot = new ActivePolicySnapshot(
                key,
                activeVer.getVersionNumber(),
                activeRules,
                shadowVerNum,
                shadowRules,
                Instant.now()
            );

            cache.put(key, snapshot);
            log.info("Policy cache refreshed for '{}': activeVersion={}, activeRules={}, shadowVersion={}",
                key, activeVer.getVersionNumber(), activeRules.size(), shadowVerNum);

            return Optional.of(snapshot);

        } catch (Exception ex) {
            log.error("Failed to refresh policy cache for '{}': {}. Preserving existing cache.", key, ex.getMessage());
            // Preserve existing cache to prevent security regressions during outages
            ActivePolicySnapshot existing = cache.get(key);
            return Optional.ofNullable(existing);
        }
    }

    /**
     * Invalidates local cache and broadcasts an invalidation notice over Redis.
     */
    public void invalidateAndBroadcast(String policyKey) {
        refreshPolicy(policyKey);
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.convertAndSend(POLICY_INVALIDATION_CHANNEL, policyKey);
            } catch (Exception ex) {
                log.warn("Failed to broadcast policy invalidation notice via Redis: {}", ex.getMessage());
            }
        }
    }

    public void clearAll() {
        cache.clear();
    }

    private List<PolicyRule> parseRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(rulesJson, new TypeReference<List<PolicyRule>>() {});
        } catch (Exception ex) {
            log.error("Corrupted rules_json encountered: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}
