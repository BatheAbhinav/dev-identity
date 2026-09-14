package abhinav.projects.dev_identity.ingestion;

import abhinav.projects.dev_identity.config.GitHubProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over the GitHub REST API. Credentials are resolved per request
 * via {@link #access(String, String)} — explicit values win, the configured
 * GITHUB_TOKEN / GITHUB_USERNAME env vars are the fallback. The token is held
 * in memory for the lifetime of one {@link GitHubAccess} and never logged.
 */
@Component
public class GitHubClient {

    private static final int PAGE_SIZE = 100;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_OBJECTS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Long>> STRING_TO_LONG =
            new ParameterizedTypeReference<>() {};

    private final GitHubProperties properties;

    public GitHubClient(GitHubProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolve credentials for one ingestion run. Modes:
     * <ul>
     *   <li>token only — the token's own user, private repos included</li>
     *   <li>username only — that user's public graph, 60 req/hr</li>
     *   <li>both, username is the token's own login — same as token only:
     *       full graph including private and org/collaborator repos</li>
     *   <li>both, username is someone else — that user's public graph,
     *       but authenticated (5000 req/hr)</li>
     *   <li>neither — rejected, so callers can fail fast with a 400</li>
     * </ul>
     */
    public GitHubAccess access(String username, String token) {
        String effectiveToken = firstNonBlank(token, properties.token());
        String effectiveUsername = firstNonBlank(username, properties.username());
        if (effectiveToken == null && effectiveUsername == null) {
            throw new IllegalArgumentException(
                    "Provide a GitHub username or a token (or set GITHUB_USERNAME / GITHUB_TOKEN).");
        }
        return new GitHubAccess(effectiveToken, effectiveUsername);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    public static class GitHubAccess {

        private final RestClient restClient;
        private final boolean authenticated;
        private final String username;
        /**
         * True when the graph is the token user's own: use /user endpoints,
         * which include private and org/collaborator repos. Resolved lazily
         * (may need one /user call) so construction stays network-free for
         * the controller's fail-fast validation.
         */
        private Boolean ownGraph;
        private Map<String, Object> tokenUser;

        private GitHubAccess(String token, String username) {
            this.username = username;
            this.authenticated = token != null;
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl("https://api.github.com")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
            if (token != null) {
                builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            this.restClient = builder.build();
        }

        /** Whether this run sees the token user's full graph (private repos). */
        public boolean isOwnGraph() {
            if (ownGraph == null) {
                if (!authenticated) {
                    ownGraph = false;
                } else if (username == null) {
                    ownGraph = true;
                } else {
                    tokenUser = restClient.get().uri("/user").retrieve().body(OBJECT);
                    String tokenLogin = tokenUser == null ? null : (String) tokenUser.get("login");
                    ownGraph = username.equalsIgnoreCase(tokenLogin);
                }
            }
            return ownGraph;
        }

        /** The user whose identity graph is being built. */
        public Map<String, Object> getSubjectUser() {
            if (isOwnGraph()) {
                if (tokenUser == null) {
                    tokenUser = restClient.get().uri("/user").retrieve().body(OBJECT);
                }
                return tokenUser;
            }
            return restClient.get().uri("/users/" + username).retrieve().body(OBJECT);
        }

        public List<Map<String, Object>> listRepos() {
            String path = isOwnGraph() ? "/user/repos" : "/users/" + username + "/repos";
            return fetchAllPages(path);
        }

        /** Language name -> bytes of code, for WRITTEN_IN weights. */
        public Map<String, Long> listLanguages(String owner, String repo) {
            return restClient.get()
                    .uri("/repos/{owner}/{repo}/languages", owner, repo)
                    .retrieve()
                    .body(STRING_TO_LONG);
        }

        /** Contributors with commit counts, for COLLABORATES_WITH weights. */
        public List<Map<String, Object>> listContributors(String owner, String repo) {
            return fetchAllPages("/repos/" + owner + "/" + repo + "/contributors");
        }

        /**
         * Traffic totals for the last 14 days. kind is "views" or "clones".
         * Requires push access to the repo; 403 otherwise.
         */
        public Map<String, Object> getTraffic(String owner, String repo, String kind) {
            return restClient.get()
                    .uri("/repos/{owner}/{repo}/traffic/{kind}", owner, repo, kind)
                    .retrieve()
                    .body(OBJECT);
        }

        /** Public org memberships (all orgs visible to the token, if any). */
        public List<Map<String, Object>> listOrganizations(String username) {
            return restClient.get()
                    .uri("/users/{username}/orgs", username)
                    .retrieve()
                    .body(LIST_OF_OBJECTS);
        }

        /**
         * Recent public events (~90 days, capped at 300 by GitHub), used to find
         * CONTRIBUTED_TO repos that don't show up in the repo listing.
         */
        public List<Map<String, Object>> listPublicEvents(String username) {
            return fetchAllPages("/users/" + username + "/events/public");
        }

        private List<Map<String, Object>> fetchAllPages(String path) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (int page = 1; ; page++) {
                List<Map<String, Object>> batch = restClient.get()
                        .uri(path + "?per_page=" + PAGE_SIZE + "&page=" + page)
                        .retrieve()
                        .body(LIST_OF_OBJECTS);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                all.addAll(batch);
                if (batch.size() < PAGE_SIZE) {
                    break;
                }
            }
            return all;
        }
    }
}
