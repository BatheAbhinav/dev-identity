package abhinav.projects.dev_identity.api;

import abhinav.projects.dev_identity.api.GraphDtos.EdgeDto;
import abhinav.projects.dev_identity.api.GraphDtos.GraphResponse;
import abhinav.projects.dev_identity.api.GraphDtos.NodeDto;
import abhinav.projects.dev_identity.api.GraphDtos.RefreshResponse;
import abhinav.projects.dev_identity.graph.EdgeRepository;
import abhinav.projects.dev_identity.graph.EdgeType;
import abhinav.projects.dev_identity.graph.GraphEdge;
import abhinav.projects.dev_identity.graph.GraphNode;
import abhinav.projects.dev_identity.graph.NodeRepository;
import abhinav.projects.dev_identity.graph.NodeType;
import abhinav.projects.dev_identity.ingestion.GitHubClient;
import abhinav.projects.dev_identity.ingestion.IngestionService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class GraphController {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    private final IngestionService ingestionService;
    private final GitHubClient gitHubClient;

    public GraphController(NodeRepository nodeRepository,
                           EdgeRepository edgeRepository,
                           IngestionService ingestionService,
                           GitHubClient gitHubClient) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.ingestionService = ingestionService;
        this.gitHubClient = gitHubClient;
    }

    @GetMapping("/graph")
    @Transactional(readOnly = true)
    public GraphResponse fullGraph() {
        List<NodeDto> nodes = nodeRepository.findAll().stream().map(NodeDto::from).toList();
        List<EdgeDto> edges = edgeRepository.findAll().stream().map(EdgeDto::from).toList();
        return new GraphResponse(nodes, edges);
    }

    @GetMapping("/nodes/{id}/neighbors")
    @Transactional(readOnly = true)
    public GraphResponse neighbors(@PathVariable Long id) {
        GraphNode center = nodeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No node with id " + id));
        List<GraphEdge> touching = edgeRepository.findAllTouching(center.getId());
        Set<GraphNode> nodes = new LinkedHashSet<>();
        nodes.add(center);
        for (GraphEdge edge : touching) {
            nodes.add(edge.getSource());
            nodes.add(edge.getTarget());
        }
        return new GraphResponse(
                nodes.stream().map(NodeDto::from).toList(),
                touching.stream().map(EdgeDto::from).toList());
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public GraphDtos.StatsResponse stats() {
        Map<NodeType, Long> nodeCounts = nodeRepository.findAll().stream()
                .collect(Collectors.groupingBy(GraphNode::getType, Collectors.counting()));
        List<GraphEdge> edges = edgeRepository.findAll();

        Map<String, Long> bytesByLanguage = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            if (edge.getType() == EdgeType.WRITTEN_IN) {
                bytesByLanguage.merge(edge.getTarget().getLabel(), (long) edge.getWeight(), Long::sum);
            }
        }
        long totalBytes = bytesByLanguage.values().stream().mapToLong(Long::longValue).sum();
        List<GraphDtos.LanguageShare> languageShares = bytesByLanguage.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new GraphDtos.LanguageShare(e.getKey(), e.getValue(),
                        totalBytes > 0 ? (double) e.getValue() / totalBytes : 0))
                .toList();

        List<GraphDtos.CollaboratorWeight> topCollaborators = edges.stream()
                .filter(e -> e.getType() == EdgeType.COLLABORATES_WITH)
                .sorted(Comparator.comparingDouble(GraphEdge::getWeight).reversed())
                .limit(10)
                .map(e -> new GraphDtos.CollaboratorWeight(e.getTarget().getLabel(), e.getWeight()))
                .toList();

        long userCount = nodeCounts.getOrDefault(NodeType.USER, 0L);
        return new GraphDtos.StatsResponse(
                nodeCounts.getOrDefault(NodeType.REPO, 0L),
                nodeCounts.getOrDefault(NodeType.LANGUAGE, 0L),
                Math.max(0, userCount - 1),
                nodeCounts.getOrDefault(NodeType.ORGANIZATION, 0L),
                languageShares,
                topCollaborators);
    }

    @GetMapping("/status")
    public GraphDtos.StatusResponse status() {
        return new GraphDtos.StatusResponse(ingestionService.isRunning(), ingestionService.getLastError());
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(
            @RequestBody(required = false) GraphDtos.RefreshRequest request) {
        String username = request == null ? null : request.username();
        String token = request == null ? null : request.token();
        try {
            gitHubClient.access(username, token);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new RefreshResponse("error", e.getMessage()));
        }
        if (ingestionService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(RefreshResponse.of("already-running"));
        }
        ingestionService.refresh(username, token);
        return ResponseEntity.accepted().body(RefreshResponse.of("started"));
    }
}
