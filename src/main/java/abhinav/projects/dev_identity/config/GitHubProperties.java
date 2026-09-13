package abhinav.projects.dev_identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub access configuration. The token is optional: when present, ingestion
 * runs authenticated (5000 req/hr, private repos); when absent, it falls back
 * to the public API (60 req/hr) and {@code username} must be set.
 */
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(String token, String username) {

    public boolean isAuthenticated() {
        return token != null && !token.isBlank();
    }
}
