package com.gatekeeper.routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for Gatekeeper routing engine and upstream integration.
 */
@Component
@ConfigurationProperties(prefix = "gatekeeper.routing")
public class GatewayRoutingProperties {

    private boolean enabled = true;
    private int defaultTimeoutMs = 5000;
    private long defaultMaxBodySizeBytes = 1048576L; // 1MB
    private Set<String> allowedHosts = new HashSet<>(Set.of("localhost", "127.0.0.1", "::1"));
    private List<RouteDefinition> routes = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getDefaultMaxBodySizeBytes() {
        return defaultMaxBodySizeBytes;
    }

    public void setDefaultMaxBodySizeBytes(long defaultMaxBodySizeBytes) {
        this.defaultMaxBodySizeBytes = defaultMaxBodySizeBytes;
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public List<RouteDefinition> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteDefinition> routes) {
        this.routes = routes;
    }

    public static class RouteDefinition {
        private String routeId;
        private String pathPattern;
        private String pathPrefix;
        private String upstreamServiceId;
        private String upstreamBaseUri;
        private Set<String> allowedMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
        private Map<String, String> methodScopes = Map.of();
        private String defaultScope;
        private boolean enabled = true;
        private int timeoutMs = 5000;
        private long maxBodySizeBytes = 1048576L;

        public String getRouteId() {
            return routeId;
        }

        public void setRouteId(String routeId) {
            this.routeId = routeId;
        }

        public String getPathPattern() {
            return pathPattern;
        }

        public void setPathPattern(String pathPattern) {
            this.pathPattern = pathPattern;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public String getUpstreamServiceId() {
            return upstreamServiceId;
        }

        public void setUpstreamServiceId(String upstreamServiceId) {
            this.upstreamServiceId = upstreamServiceId;
        }

        public String getUpstreamBaseUri() {
            return upstreamBaseUri;
        }

        public void setUpstreamBaseUri(String upstreamBaseUri) {
            this.upstreamBaseUri = upstreamBaseUri;
        }

        public Set<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(Set<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public Map<String, String> getMethodScopes() {
            return methodScopes;
        }

        public void setMethodScopes(Map<String, String> methodScopes) {
            this.methodScopes = methodScopes;
        }

        public String getDefaultScope() {
            return defaultScope;
        }

        public void setDefaultScope(String defaultScope) {
            this.defaultScope = defaultScope;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getMaxBodySizeBytes() {
            return maxBodySizeBytes;
        }

        public void setMaxBodySizeBytes(long maxBodySizeBytes) {
            this.maxBodySizeBytes = maxBodySizeBytes;
        }
    }
}
