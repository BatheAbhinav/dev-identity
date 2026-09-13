package abhinav.projects.dev_identity.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A directed, typed, optionally weighted edge. One edge per
 * (source, target, type) triple; weight semantics depend on the type
 * (see {@link EdgeType}).
 */
@Entity
@Table(name = "edges", uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "target_id", "type"}))
public class GraphEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private GraphNode source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id")
    private GraphNode target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EdgeType type;

    @Column(nullable = false)
    private double weight;

    protected GraphEdge() {
    }

    public GraphEdge(GraphNode source, GraphNode target, EdgeType type, double weight) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.weight = weight;
    }

    public Long getId() {
        return id;
    }

    public GraphNode getSource() {
        return source;
    }

    public GraphNode getTarget() {
        return target;
    }

    public EdgeType getType() {
        return type;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }
}
