package abhinav.projects.dev_identity.api;

import abhinav.projects.dev_identity.graph.EdgeType;
import abhinav.projects.dev_identity.graph.GraphEdge;
import abhinav.projects.dev_identity.graph.GraphNode;
import abhinav.projects.dev_identity.graph.NodeType;
import java.util.List;

/** Wire-format records for the graph API, kept flat for easy D3 consumption. */
public final class GraphDtos {

    private GraphDtos() {
    }

    public record NodeDto(Long id, NodeType type, String key, String label) {

        static NodeDto from(GraphNode node) {
            return new NodeDto(node.getId(), node.getType(), node.getExternalKey(), node.getLabel());
        }
    }

    public record EdgeDto(Long id, Long source, Long target, EdgeType type, double weight) {

        static EdgeDto from(GraphEdge edge) {
            return new EdgeDto(edge.getId(), edge.getSource().getId(), edge.getTarget().getId(),
                    edge.getType(), edge.getWeight());
        }
    }

    public record GraphResponse(List<NodeDto> nodes, List<EdgeDto> edges) {
    }

    /** Both fields optional; blank falls back to GITHUB_USERNAME / GITHUB_TOKEN. */
    public record RefreshRequest(String username, String token) {
    }

    public record RefreshResponse(String status, String message) {

        public static RefreshResponse of(String status) {
            return new RefreshResponse(status, null);
        }
    }

    public record StatusResponse(boolean refreshing, String lastError) {
    }

    /** share is the fraction of total bytes across all repos (sums to 1). */
    public record LanguageShare(String label, long bytes, double share) {
    }

    public record CollaboratorWeight(String label, double weight) {
    }

    public record RepoTrafficDto(String label, long views, long uniqueVisitors,
                                 long clones, long uniqueCloners) {
    }

    /** GitHub's rolling 14-day window; null when no traffic data was ingested. */
    public record TrafficStats(long views, long uniqueVisitors, long clones, long uniqueCloners,
                               List<RepoTrafficDto> topByViews,
                               List<RepoTrafficDto> topByClones) {
    }

    public record StatsResponse(long repos, long languages, long collaborators, long organizations,
                                List<LanguageShare> languageShares,
                                List<CollaboratorWeight> topCollaborators,
                                TrafficStats traffic) {
    }
}
