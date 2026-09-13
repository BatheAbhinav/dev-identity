package abhinav.projects.dev_identity.ingestion;

import abhinav.projects.dev_identity.graph.EdgeRepository;
import abhinav.projects.dev_identity.graph.EdgeType;
import abhinav.projects.dev_identity.graph.GraphEdge;
import abhinav.projects.dev_identity.graph.GraphNode;
import abhinav.projects.dev_identity.graph.NodeRepository;
import abhinav.projects.dev_identity.graph.NodeType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

/**
 * Pulls GitHub data and rebuilds the graph from scratch on each run, inside
 * one transaction — a failed run rolls back and leaves the previous graph
 * intact. Refresh runs async so POST /refresh can return 202 immediately;
 * {@link #isRunning()} reports progress to the API layer.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final GitHubClient gitHubClient;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String lastError;

    public IngestionService(GitHubClient gitHubClient,
                            NodeRepository nodeRepository,
                            EdgeRepository edgeRepository) {
        this.gitHubClient = gitHubClient;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Failure summary of the most recent run, or null if it succeeded. */
    public String getLastError() {
        return lastError;
    }

    @Async
    @Transactional
    public void refresh(String username, String token) {
        if (!running.compareAndSet(false, true)) {
            log.info("Refresh already in progress, skipping");
            return;
        }
        lastError = null;
        try {
            log.info("Starting GitHub ingestion");
            GitHubClient.GitHubAccess gh = gitHubClient.access(username, token);
            // Fetch the cheap, load-bearing data before wiping anything so a
            // bad token/username fails without touching the stored graph.
            String subjectLogin = (String) gh.getSubjectUser().get("login");
            List<Map<String, Object>> repos = gh.listRepos();
            log.info("Ingesting {} repos for {} ({})", repos.size(), subjectLogin,
                    gh.isOwnGraph() ? "own graph: private + org repos included" : "public profile only");

            edgeRepository.deleteAllInBatch();
            nodeRepository.deleteAllInBatch();

            RunContext ctx = new RunContext(new LinkedHashMap<>(), new LinkedHashMap<>());
            GraphNode subjectNode = node(ctx, NodeType.USER, "user:" + subjectLogin, subjectLogin);

            Map<String, Double> collaborationWeights = new LinkedHashMap<>();
            Set<String> listedRepos = new HashSet<>();

            for (Map<String, Object> repo : repos) {
                String fullName = (String) repo.get("full_name");
                listedRepos.add(fullName);
                @SuppressWarnings("unchecked")
                Map<String, Object> owner = (Map<String, Object>) repo.get("owner");
                String ownerLogin = (String) owner.get("login");

                GraphNode repoNode = node(ctx, NodeType.REPO, "repo:" + fullName, fullName);
                EdgeType relation = subjectLogin.equalsIgnoreCase(ownerLogin)
                        ? EdgeType.OWNS
                        : EdgeType.CONTRIBUTED_TO;
                edge(ctx, subjectNode, repoNode, relation, 1.0);

                String[] ownerAndName = fullName.split("/", 2);
                try {
                    ingestLanguages(ctx, gh, repoNode, ownerAndName[0], ownerAndName[1]);
                    collectCollaborations(gh, subjectLogin, ownerAndName[0], ownerAndName[1], collaborationWeights);
                } catch (RestClientResponseException e) {
                    // Empty repos 404/409 on some endpoints; don't abort the run.
                    log.warn("Skipping details for {}: GitHub returned {}", fullName, e.getStatusCode());
                }
            }

            for (Map.Entry<String, Double> collaboration : collaborationWeights.entrySet()) {
                String login = collaboration.getKey();
                GraphNode collaborator = node(ctx, NodeType.USER, "user:" + login, login);
                edge(ctx, subjectNode, collaborator, EdgeType.COLLABORATES_WITH, collaboration.getValue());
            }

            ingestOrganizations(ctx, gh, subjectNode, subjectLogin);
            ingestRecentContributions(ctx, gh, subjectNode, subjectLogin, listedRepos);

            log.info("Ingestion complete for {}: {} nodes, {} edges",
                    subjectLogin, ctx.nodes().size(), ctx.edges().size());
        } catch (RuntimeException e) {
            lastError = summarize(e);
            log.error("Ingestion failed; rolling back, previous graph left unchanged", e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private void ingestLanguages(RunContext ctx, GitHubClient.GitHubAccess gh, GraphNode repoNode,
                                 String owner, String repo) {
        Map<String, Long> languages = gh.listLanguages(owner, repo);
        if (languages == null || languages.isEmpty()) {
            return;
        }
        languages.forEach((name, bytes) -> {
            if (bytes == null || bytes <= 0) {
                return;
            }
            GraphNode language = node(ctx, NodeType.LANGUAGE, "lang:" + name, name);
            edge(ctx, repoNode, language, EdgeType.WRITTEN_IN, bytes);
        });
    }

    /**
     * COLLABORATES_WITH weight is a co-contribution proxy: for each shared
     * repo, min(subject's commits, collaborator's commits), summed across
     * repos. True shared-commit analysis would need full commit history.
     */
    private void collectCollaborations(GitHubClient.GitHubAccess gh, String subjectLogin,
                                       String owner, String repo, Map<String, Double> weights) {
        List<Map<String, Object>> contributors = gh.listContributors(owner, repo);
        long subjectCommits = contributors.stream()
                .filter(c -> subjectLogin.equalsIgnoreCase((String) c.get("login")))
                .mapToLong(c -> ((Number) c.get("contributions")).longValue())
                .findFirst()
                .orElse(0L);
        if (subjectCommits == 0) {
            return;
        }
        for (Map<String, Object> contributor : contributors) {
            String login = (String) contributor.get("login");
            if (login == null || login.equalsIgnoreCase(subjectLogin) || isBot(contributor)) {
                continue;
            }
            long commits = ((Number) contributor.get("contributions")).longValue();
            weights.merge(login, (double) Math.min(subjectCommits, commits), Double::sum);
        }
    }

    private void ingestOrganizations(RunContext ctx, GitHubClient.GitHubAccess gh,
                                     GraphNode subjectNode, String subjectLogin) {
        List<Map<String, Object>> orgs = gh.listOrganizations(subjectLogin);
        if (orgs == null) {
            return;
        }
        for (Map<String, Object> org : orgs) {
            String login = (String) org.get("login");
            GraphNode orgNode = node(ctx, NodeType.ORGANIZATION, "org:" + login, login);
            edge(ctx, subjectNode, orgNode, EdgeType.MEMBER_OF, 1.0);
        }
    }

    /**
     * CONTRIBUTED_TO repos that don't appear in the repo listing, recovered
     * from recent public events (GitHub only keeps ~90 days / 300 events, so
     * this is best-effort). Weight = number of push/PR events seen.
     */
    private void ingestRecentContributions(RunContext ctx, GitHubClient.GitHubAccess gh,
                                           GraphNode subjectNode, String subjectLogin,
                                           Set<String> listedRepos) {
        List<Map<String, Object>> events;
        try {
            events = gh.listPublicEvents(subjectLogin);
        } catch (RestClientResponseException e) {
            log.warn("Skipping event-based contributions: GitHub returned {}", e.getStatusCode());
            return;
        }
        Map<String, Long> eventCounts = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            Object type = event.get("type");
            if (!"PushEvent".equals(type) && !"PullRequestEvent".equals(type)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> repo = (Map<String, Object>) event.get("repo");
            String fullName = repo == null ? null : (String) repo.get("name");
            if (fullName == null || listedRepos.contains(fullName)
                    || fullName.toLowerCase().startsWith(subjectLogin.toLowerCase() + "/")) {
                continue;
            }
            eventCounts.merge(fullName, 1L, Long::sum);
        }
        eventCounts.forEach((fullName, count) -> {
            GraphNode repoNode = node(ctx, NodeType.REPO, "repo:" + fullName, fullName);
            edge(ctx, subjectNode, repoNode, EdgeType.CONTRIBUTED_TO, count);
        });
    }

    /** Short, token-free summary for the /status endpoint. */
    private static String summarize(RuntimeException e) {
        if (e instanceof RestClientResponseException http) {
            String hint = http.getStatusCode().value() == 403
                    ? " (likely the GitHub rate limit — 60 req/hr without a token; add a PAT)"
                    : "";
            return "GitHub returned " + http.getStatusCode().value() + hint;
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private boolean isBot(Map<String, Object> contributor) {
        return "Bot".equals(contributor.get("type"))
                || String.valueOf(contributor.get("login")).endsWith("[bot]");
    }

    private GraphNode node(RunContext ctx, NodeType type, String key, String label) {
        return ctx.nodes().computeIfAbsent(key, k -> nodeRepository.save(new GraphNode(type, k, label)));
    }

    /** First writer wins per (source, type, target); later calls are no-ops. */
    private void edge(RunContext ctx, GraphNode source, GraphNode target, EdgeType type, double weight) {
        String key = source.getExternalKey() + "|" + type + "|" + target.getExternalKey();
        ctx.edges().computeIfAbsent(key, k -> edgeRepository.save(new GraphEdge(source, target, type, weight)));
    }

    private record RunContext(Map<String, GraphNode> nodes, Map<String, GraphEdge> edges) {
    }
}
