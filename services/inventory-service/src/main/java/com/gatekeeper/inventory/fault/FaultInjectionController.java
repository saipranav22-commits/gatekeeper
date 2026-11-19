package com.gatekeeper.inventory.fault;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/fault")
@Profile({"local", "test", "dev"})
public class FaultInjectionController {

    private final FaultSimulationManager faultManager;

    public FaultInjectionController(FaultSimulationManager faultManager) {
        this.faultManager = faultManager;
    }

    public record FaultConfigRequest(
        long delayMs,
        int statusCode,
        int failureCount
    ) {}

    @PostMapping("/configure")
    public ResponseEntity<Map<String, Object>> configureFault(@RequestBody FaultConfigRequest config) {
        faultManager.configure(config.delayMs(), config.statusCode(), config.failureCount());
        return ResponseEntity.ok(Map.of(
            "status", "CONFIGURED",
            "delayMs", config.delayMs(),
            "statusCode", config.statusCode(),
            "failureCount", config.failureCount()
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetFault() {
        faultManager.reset();
        return ResponseEntity.ok(Map.of("status", "RESET"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getFaultStatus() {
        return ResponseEntity.ok(Map.of(
            "active", faultManager.isActive(),
            "delayMs", faultManager.getDelayMs(),
            "statusCode", faultManager.getStatusCode(),
            "remainingFailures", faultManager.getRemainingFailures(),
            "totalFaultsInjected", faultManager.getTotalFaultsInjected(),
            "totalAttemptsReceived", faultManager.getTotalAttemptsReceived()
        ));
    }

    @GetMapping("/attempts/{correlationId}")
    public ResponseEntity<Map<String, Object>> getAttemptsForCorrelationId(@PathVariable String correlationId) {
        int attempts = faultManager.getAttemptsForCorrelationId(correlationId);
        return ResponseEntity.ok(Map.of(
            "correlationId", correlationId,
            "attempts", attempts
        ));
    }
}
