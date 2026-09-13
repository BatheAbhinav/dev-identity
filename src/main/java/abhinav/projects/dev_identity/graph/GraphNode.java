package abhinav.projects.dev_identity.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A vertex in the identity graph. {@code externalKey} is the stable natural
 * key from GitHub (e.g. "user:octocat", "repo:octocat/hello-world",
 * "lang:Java") so re-ingestion upserts instead of duplicating.
 */
@Entity
@Table(name = "nodes", uniqueConstraints = @UniqueConstraint(columnNames = "external_key"))
public class GraphNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeType type;

    @Column(name = "external_key", nullable = false)
    private String externalKey;

    @Column(nullable = false)
    private String label;

    protected GraphNode() {
    }

    public GraphNode(NodeType type, String externalKey, String label) {
        this.type = type;
        this.externalKey = externalKey;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public NodeType getType() {
        return type;
    }

    public String getExternalKey() {
        return externalKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
