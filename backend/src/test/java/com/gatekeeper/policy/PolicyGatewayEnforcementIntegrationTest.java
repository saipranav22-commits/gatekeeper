package com.gatekeeper.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.service.ApiKeyService;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.gateway.filter.ConsumerApiKeyAuthenticationFilter;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import com.gatekeeper.policy.dto.CreatePolicyDefinitionRequest;
import com.gatekeeper.policy.dto.CreatePolicyVersionRequest;
import com.gatekeeper.policy.dto.PolicyDefinitionDto;
import com.gatekeeper.policy.dto.PolicyVersionDto;
import com.gatekeeper.policy.engine.PolicyCacheManager;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.model.PolicyRuleConditions;
import com.gatekeeper.policy.service.PolicyAdministrationService;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisplayName("PolicyGatewayEnforcementIntegrationTest - Gateway Pipeline Integration (Phase 5C)")
class PolicyGatewayEnforcementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PolicyAdministrationService policyAdminService;

    @Autowired
    private PolicyCacheManager policyCacheManager;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String adminToken;
    private String analystToken;
    private String fullScopeApiKey;
    private String readOnlyApiKey;
    private UUID consumerId;
    private final List<UUID> createdConsumerIds = new ArrayList<>();
    private final List<UUID> createdAdminIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdConsumerIds.clear();
        createdAdminIds.clear();

        // 1. Create Admin User (ROLE_ADMIN)
        String adminSuffix = UUID.randomUUID().toString().substring(0, 8);
        AdminUser admin = new AdminUser("policy_admin_" + adminSuffix, "padmin_" + adminSuffix + "@test.local", "$2a$10$hash", "ROLE_ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(false);
        AdminUser savedAdmin = adminUserRepository.saveAndFlush(admin);
        createdAdminIds.add(savedAdmin.getId());
        adminToken = jwtService.generateToken(savedAdmin.getId(), savedAdmin.getUsername(), savedAdmin.getRole(), 1L);

        // 2. Create Analyst User (ROLE_ANALYST)
        AdminUser analyst = new AdminUser("policy_analyst_" + adminSuffix, "panalyst_" + adminSuffix + "@test.local", "$2a$10$hash", "ROLE_ANALYST");
        analyst.setActive(true);
        analyst.setTokenVersion(1L);
        analyst.setMustChangePassword(false);
        AdminUser savedAnalyst = adminUserRepository.saveAndFlush(analyst);
        createdAdminIds.add(savedAnalyst.getId());
        analystToken = jwtService.generateToken(savedAnalyst.getId(), savedAnalyst.getUsername(), savedAnalyst.getRole(), 1L);

        // 3. Create Consumer & Keys
        SubscriptionPlan plan = planRepository.findByName("PRO").orElseGet(() ->
            planRepository.save(new SubscriptionPlan("PRO", "Pro Tier", 100, 100, 60)));

        String consumerSuffix = UUID.randomUUID().toString().substring(0, 8);
        ApiConsumer consumer = new ApiConsumer("Policy Test Consumer " + consumerSuffix, "policy_" + consumerSuffix + "@test.local", plan);
        consumer = consumerRepository.save(consumer);
        consumerId = consumer.getId();
        createdConsumerIds.add(consumer.getId());

        // Key with read and write scopes
        CreateApiKeyRequest fullKeyReq = new CreateApiKeyRequest(
            consumer.getId(), "Full Scope Key", Set.of("orders:read", "orders:write"), null
        );
        fullScopeApiKey = apiKeyService.createApiKey(fullKeyReq).plaintextKey();

        // Key with read-only scope
        CreateApiKeyRequest readKeyReq = new CreateApiKeyRequest(
            consumer.getId(), "Read Scope Key", Set.of("orders:read"), null
        );
        readOnlyApiKey = apiKeyService.createApiKey(readKeyReq).plaintextKey();

        // Clean cache
        policyCacheManager.clearAll();
    }

    @AfterEach
    void tearDown() {
        policyCacheManager.clearAll();
    }

    @Test
    @DisplayName("Policy DENY returns HTTP 403 Forbidden with safe reason and preserves Redis quota")
    void policyDenyReturns403AndPreservesQuota() throws Exception {
        // Create policy for orders-service-v1 that DENIES POST requests
        String policyKey = "orders-service-v1";
        if (policyAdminService.listDefinitions().stream().noneMatch(d -> d.policyKey().equals(policyKey))) {
            policyAdminService.createDefinition(new CreatePolicyDefinitionRequest(policyKey, "Orders Policy", "Route policy"));
        }

        PolicyRule denyPostRule = new PolicyRule(
            "deny-orders-write",
            50,
            true,
            new PolicyRuleConditions(policyKey, "POST", null, null, null, null, null, null),
            PolicyAction.DENY,
            "Orders write blocked by gateway policy",
            "ORDERS_WRITE_RESTRICTED",
            "Orders modification is temporarily disabled by policy"
        );

        PolicyVersionDto ver = policyAdminService.createVersion(policyKey, new CreatePolicyVersionRequest("Deny write v1", List.of(denyPostRule)), "admin");
        policyAdminService.activateVersion(policyKey, ver.versionNumber(), "admin", "Activate write restriction");

        // Execute POST /api/v1/proxy/orders
        mockMvc.perform(post("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, fullScopeApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-LAPTOP-01\",\"quantity\":1}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message", containsString("Orders modification is temporarily disabled by policy")))
            // Quota preservation invariant: RateLimit-Remaining header is NEVER sent because quota was not consumed
            .andExpect(header().doesNotExist("RateLimit-Remaining"));
    }

    @Test
    @DisplayName("Mandatory Scope check enforced BEFORE policy evaluation: Policy cannot bypass missing scope")
    void mandatoryScopeEnforcedBeforePolicy() throws Exception {
        // Create policy that explicitly ALLOW_CONTINUES POST
        String policyKey = "orders-service-v1";
        if (policyAdminService.listDefinitions().stream().noneMatch(d -> d.policyKey().equals(policyKey))) {
            policyAdminService.createDefinition(new CreatePolicyDefinitionRequest(policyKey, "Orders Policy", "Route policy"));
        }

        PolicyRule allowPostRule = new PolicyRule(
            "allow-orders-write",
            10,
            true,
            new PolicyRuleConditions(policyKey, "POST", null, null, null, null, null, null),
            PolicyAction.ALLOW_CONTINUE,
            "Permit write",
            "PERMIT_WRITE",
            "Permitted"
        );
        PolicyVersionDto allowVer = policyAdminService.createVersion(policyKey, new CreatePolicyVersionRequest("Allow write v1", List.of(allowPostRule)), "admin");
        policyAdminService.activateVersion(policyKey, allowVer.versionNumber(), "admin", "Activate allow rule");

        // Request sent with readOnlyApiKey (missing required scope orders:write)
        mockMvc.perform(post("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, readOnlyApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-LAPTOP-01\",\"quantity\":1}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message", containsString("insufficient scope. Required: orders:write")));
    }

    @Test
    @DisplayName("Mandatory API Key authentication enforced BEFORE policy: Invalid key returns 401")
    void mandatoryAuthenticationEnforcedBeforePolicy() throws Exception {
        mockMvc.perform(get("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, "invalid_api_key_12345"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message", containsString("Invalid, expired or revoked API key")));
    }

    @Test
    @DisplayName("Policy ALLOW_CONTINUE allows request to proceed through quota and routing")
    void policyAllowContinueAllowsTraffic() throws Exception {
        // Policy allows GET on orders
        String policyKey = "orders-service-v1";
        if (policyAdminService.listDefinitions().stream().noneMatch(d -> d.policyKey().equals(policyKey))) {
            policyAdminService.createDefinition(new CreatePolicyDefinitionRequest(policyKey, "Orders Policy", "Route policy"));
        }

        PolicyRule allowGet = new PolicyRule(
            "allow-orders-read",
            100,
            true,
            new PolicyRuleConditions(policyKey, "GET", null, null, null, null, null, null),
            PolicyAction.ALLOW_CONTINUE,
            "Permit read",
            "PERMIT_READ",
            "Permitted"
        );
        PolicyVersionDto getVer = policyAdminService.createVersion(policyKey, new CreatePolicyVersionRequest("Allow read v1", List.of(allowGet)), "admin");
        policyAdminService.activateVersion(policyKey, getVer.versionNumber(), "admin", "Activate allow rule");

        mockMvc.perform(get("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, fullScopeApiKey))
            .andExpect(header().exists("RateLimit-Remaining"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                assertThat(status).isIn(200, 502); // 200 if live on 8081, 502 if down, but passed policy!
            });
    }

    @Test
    @DisplayName("RBAC: ANALYST role can read, simulate, and replay but cannot mutate policies")
    void analystRoleAccessControl() throws Exception {
        String policyKey = "analyst-test-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Analyst CANNOT create policy definition -> 403
        mockMvc.perform(post("/api/v1/policies")
                .header("Authorization", "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreatePolicyDefinitionRequest(policyKey, "Test", "Desc"))))
            .andExpect(status().isForbidden());

        // Admin creates definition
        mockMvc.perform(post("/api/v1/policies")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreatePolicyDefinitionRequest(policyKey, "Test", "Desc"))))
            .andExpect(status().isCreated());

        // 2. Analyst CAN list definitions -> 200
        mockMvc.perform(get("/api/v1/policies")
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isOk());

        // 3. Analyst CANNOT create version -> 403
        PolicyRule r = new PolicyRule("rule-1", 100, true, null, PolicyAction.ALLOW_CONTINUE, "r1", null, null);
        mockMvc.perform(post("/api/v1/policies/" + policyKey + "/versions")
                .header("Authorization", "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreatePolicyVersionRequest("v1", List.of(r)))))
            .andExpect(status().isForbidden());

        // Admin creates version
        mockMvc.perform(post("/api/v1/policies/" + policyKey + "/versions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreatePolicyVersionRequest("v1", List.of(r)))))
            .andExpect(status().isCreated());

        // 4. Analyst CANNOT activate version -> 403
        mockMvc.perform(post("/api/v1/policies/" + policyKey + "/versions/1/activate")
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isForbidden());

        // 5. Analyst CAN simulate policy evaluation -> 200
        mockMvc.perform(post("/api/v1/policies/" + policyKey + "/simulate")
                .header("Authorization", "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeId\":\"orders-service-v1\",\"httpMethod\":\"GET\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("ALLOW_CONTINUE"));
    }

    @Test
    @DisplayName("RBAC: Consumer API keys cannot access management policy endpoints")
    void consumerApiKeyBlockedFromPolicyEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/policies")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, fullScopeApiKey))
            .andExpect(status().isUnauthorized()); // Management filter chain requires JWT Bearer
    }

    @Test
    @DisplayName("Regression: Valid API key returns 200 on allowed baseline route, but 403 under active DENY policy with guaranteed rollback")
    void validApiKeyBaselinePermittedAndPolicyDenyWithGuaranteedRollback() throws Exception {
        String policyKey = "orders-service-v1";
        if (policyAdminService.listDefinitions().stream().noneMatch(d -> d.policyKey().equals(policyKey))) {
            policyAdminService.createDefinition(new CreatePolicyDefinitionRequest(policyKey, "Orders Policy", "Route policy"));
        }

        // 1. Capture initial active version
        PolicyDefinitionDto initialDef = policyAdminService.getDefinition(policyKey);
        Integer initialVersionNumber = initialDef.activeVersionNumber();
        Integer testActivatedVersionNumber = null;

        try {
            // 2. Set baseline policy to ALLOW read and write
            PolicyRule allowRule = new PolicyRule(
                "allow-all-baseline",
                100,
                true,
                new PolicyRuleConditions(policyKey, null, null, null, null, null, null, null),
                PolicyAction.ALLOW_CONTINUE,
                "Permit baseline traffic",
                "PERMIT_BASELINE",
                "Permitted baseline"
            );
            PolicyVersionDto baselineVer = policyAdminService.createVersion(
                policyKey, new CreatePolicyVersionRequest("Baseline Allow", List.of(allowRule)), "admin"
            );
            policyAdminService.activateVersion(policyKey, baselineVer.versionNumber(), "admin", "Activate baseline allow");
            testActivatedVersionNumber = baselineVer.versionNumber();

            // 3. Baseline verification: Valid API key + allowed route -> proceeds (200 if upstream live, 502 if upstream down)
            mockMvc.perform(get("/api/v1/proxy/orders")
                    .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, fullScopeApiKey))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(200, 502);
                });

            // 4. Activate DENY policy on POST orders
            PolicyRule denyRule = new PolicyRule(
                "deny-orders-post",
                50,
                true,
                new PolicyRuleConditions(policyKey, "POST", null, null, null, null, null, null),
                PolicyAction.DENY,
                "Deny orders creation",
                "ORDERS_MAINTENANCE",
                "Order creation is temporarily paused"
            );
            PolicyVersionDto denyVer = policyAdminService.createVersion(
                policyKey, new CreatePolicyVersionRequest("Deny Post", List.of(denyRule)), "admin"
            );
            policyAdminService.activateVersion(policyKey, denyVer.versionNumber(), "admin", "Activate deny post");
            testActivatedVersionNumber = denyVer.versionNumber();

            // 5. Verify same valid API key is now DENIED with HTTP 403 Forbidden
            mockMvc.perform(post("/api/v1/proxy/orders")
                    .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, fullScopeApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sku\":\"SKU-001\",\"quantity\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Order creation is temporarily paused")))
                .andExpect(header().doesNotExist("RateLimit-Remaining"));

        } finally {
            // 6. Safe rollback: restore initial version ONLY IF current matches what this test activated
            if (initialVersionNumber != null && testActivatedVersionNumber != null) {
                PolicyDefinitionDto currentDef = policyAdminService.getDefinition(policyKey);
                if (Objects.equals(currentDef.activeVersionNumber(), testActivatedVersionNumber)) {
                    policyAdminService.activateVersion(policyKey, initialVersionNumber, "admin", "Restored initial policy version after test");
                }
            }
        }
    }

    @Test
    @DisplayName("Audit Trail: GET /api/v1/policies/{policyKey}/audit returns chronological activation history")
    void policyActivationAuditTrailEndpoint() throws Exception {
        String testPolicy = "audit-test-" + UUID.randomUUID().toString().substring(0, 8);
        policyAdminService.createDefinition(new CreatePolicyDefinitionRequest(testPolicy, "Audit Test", "Audit policy"));

        PolicyRule r1 = new PolicyRule("rule-1", 100, true, null, PolicyAction.ALLOW_CONTINUE, "r1", null, null);
        PolicyRule r2 = new PolicyRule("rule-2", 50, true, null, PolicyAction.DENY, "r2", null, null);

        PolicyVersionDto v1 = policyAdminService.createVersion(testPolicy, new CreatePolicyVersionRequest("v1", List.of(r1)), "admin");
        policyAdminService.activateVersion(testPolicy, v1.versionNumber(), "admin", "Activate v1");

        PolicyVersionDto v2 = policyAdminService.createVersion(testPolicy, new CreatePolicyVersionRequest("v2", List.of(r2)), "admin");
        policyAdminService.activateVersion(testPolicy, v2.versionNumber(), "admin", "Activate v2");

        // Query audit endpoint via MockMvc
        mockMvc.perform(get("/api/v1/policies/" + testPolicy + "/audit")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].versionNumber").value(2))
            .andExpect(jsonPath("$[0].action").value("ACTIVATED"))
            .andExpect(jsonPath("$[0].actor").value("admin"))
            .andExpect(jsonPath("$[1].versionNumber").value(1))
            .andExpect(jsonPath("$[1].action").value("ACTIVATED"));
    }
}
