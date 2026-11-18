package com.gatekeeper.chaos.engine;

import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safety & Blast-Radius enforcement guard for Gatekeeper Chaos Engineering.
 *
 * <p>Strict Guarantees:
 * <ul>
 *   <li>Active ONLY in local/test/dev profiles (strictly forbidden in production).</li>
 *   <li>Permits fault injection ONLY against Gatekeeper-owned local services.</li>
 *   <li>Rejects arbitrary external URLs, ports, or processes.</li>
 *   <li>Enforces hard ceilings on request volume, concurrency, and experiment duration.</li>
 *   <li>Prevents multiple simultaneous disruptive experiments against the same target.</li>
 * </ul>
 */
@Component
@Profile({"local", "test", "dev", "default"})
public class ChaosSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(ChaosSafetyGuard.class);

    public static final int MAX_REQUEST_COUNT = 500;
    public static final int MAX_CONCURRENCY = 50;
    public static final long MAX_FAULT_DURATION_MS = 60_000L;
    public static final long EXPERIMENT_TIMEOUT_MS = 120_000L;

    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "localhost", "127.0.0.1", "::1"
    );

    private static final Set<Integer> ALLOWED_PORTS = Set.of(
        8080, // Gatekeeper Gateway
        8081, // Orders upstream
        8082, // Inventory upstream
        6380  // Gatekeeper Redis local port
    );

    private final Environment environment;
    private final Map<ChaosTarget, String> activeTargetExperiments = new ConcurrentHashMap<>();

    public ChaosSafetyGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * Verifies that the current execution environment is safe for chaos experiments.
     * Throws SecurityException if run in production or non-permitted profile.
     */
    public void validateEnvironment() {
        if (environment == null) {
            return; // In standalone unit tests without Spring context
        }

        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains("prod") || activeProfiles.contains("production")) {
            throw new SecurityException("CRITICAL SAFETY VIOLATION: Chaos experiments are strictly forbidden in production profile!");
        }

        boolean hasPermittedProfile = activeProfiles.isEmpty()
            || activeProfiles.contains("local")
            || activeProfiles.contains("test")
            || activeProfiles.contains("dev")
            || activeProfiles.contains("default");

        if (!hasPermittedProfile) {
            throw new SecurityException("Chaos experiments can only run in local, test, or dev profiles. Active: " + activeProfiles);
        }
    }

    /**
     * Validates target service allowlist and acquires single-active-experiment lock.
     */
    public synchronized void acquireTargetLock(ChaosExperiment experiment) {
        validateEnvironment();

        ChaosTarget target = experiment.getTarget();
        if (target == null) {
            throw new IllegalArgumentException("Chaos target must not be null");
        }

        String existing = activeTargetExperiments.get(target);
        if (existing != null && !existing.equals(experiment.getId())) {
            throw new IllegalStateException("A disruptive experiment (" + existing + ") is already active on target " + target.getServiceKey());
        }

        // Validate local target URI
        validateTargetUri(target.getLocalTargetUri());

        // Validate bounded parameters
        Map<String, Object> params = experiment.getParameters();
        if (params.containsKey("requestCount")) {
            int count = ((Number) params.get("requestCount")).intValue();
            if (count <= 0 || count > MAX_REQUEST_COUNT) {
                throw new IllegalArgumentException("requestCount must be between 1 and " + MAX_REQUEST_COUNT + ", got: " + count);
            }
        }

        if (params.containsKey("concurrency")) {
            int conc = ((Number) params.get("concurrency")).intValue();
            if (conc <= 0 || conc > MAX_CONCURRENCY) {
                throw new IllegalArgumentException("concurrency must be between 1 and " + MAX_CONCURRENCY + ", got: " + conc);
            }
        }

        if (params.containsKey("durationMs")) {
            long dur = ((Number) params.get("durationMs")).longValue();
            if (dur <= 0 || dur > MAX_FAULT_DURATION_MS) {
                throw new IllegalArgumentException("durationMs must be between 1 and " + MAX_FAULT_DURATION_MS + " ms, got: " + dur);
            }
        }

        activeTargetExperiments.put(target, experiment.getId());
        log.info("SAFETY GUARD: Acquired exclusive chaos lock on target '{}' for experiment '{}'",
            target.getServiceKey(), experiment.getId());
    }

    /**
     * Releases target lock cleanly after experiment completion or failure.
     */
    public synchronized void releaseTargetLock(ChaosExperiment experiment) {
        if (experiment != null && experiment.getTarget() != null) {
            activeTargetExperiments.remove(experiment.getTarget(), experiment.getId());
            log.info("SAFETY GUARD: Released chaos lock on target '{}' for experiment '{}'",
                experiment.getTarget().getServiceKey(), experiment.getId());
        }
    }

    /**
     * Validates that a URI is on the loopback interface and on an authorized local port.
     */
    public void validateTargetUri(String uriString) {
        if (uriString == null || uriString.equalsIgnoreCase("internal")) {
            return;
        }

        try {
            URI uri = URI.create(uriString);
            String host = uri.getHost();
            int port = uri.getPort();

            if (host != null && !ALLOWED_HOSTS.contains(host.toLowerCase())) {
                throw new SecurityException("BLAST-RADIUS VIOLATION: Unauthorized host '" + host + "'. Only loopback localhost allowed.");
            }

            if (port != -1 && !ALLOWED_PORTS.contains(port)) {
                throw new SecurityException("BLAST-RADIUS VIOLATION: Unauthorized port '" + port + "'. Allowed ports: " + ALLOWED_PORTS);
            }
        } catch (IllegalArgumentException ex) {
            throw new SecurityException("Invalid target URI: " + uriString, ex);
        }
    }

    public Map<ChaosTarget, String> getActiveTargetExperiments() {
        return Collections.unmodifiableMap(activeTargetExperiments);
    }
}
