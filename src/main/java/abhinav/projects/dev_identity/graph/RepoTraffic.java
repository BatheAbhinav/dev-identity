package abhinav.projects.dev_identity.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Traffic snapshot for one repo (GitHub's rolling 14-day window). Kept out of
 * the nodes/edges tables because traffic is a property of a repo, not a
 * relationship. Rebuilt wholesale on each refresh, like the graph. Only
 * populated in token mode — the traffic API requires push access.
 */
@Entity
@Table(name = "repo_traffic", uniqueConstraints = @UniqueConstraint(columnNames = "repo_key"))
public class RepoTraffic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_key", nullable = false)
    private String repoKey;

    @Column(nullable = false)
    private String repoLabel;

    private long views;
    private long uniqueVisitors;
    private long clones;
    private long uniqueCloners;

    protected RepoTraffic() {
    }

    public RepoTraffic(String repoKey, String repoLabel,
                       long views, long uniqueVisitors, long clones, long uniqueCloners) {
        this.repoKey = repoKey;
        this.repoLabel = repoLabel;
        this.views = views;
        this.uniqueVisitors = uniqueVisitors;
        this.clones = clones;
        this.uniqueCloners = uniqueCloners;
    }

    public String getRepoKey() {
        return repoKey;
    }

    public String getRepoLabel() {
        return repoLabel;
    }

    public long getViews() {
        return views;
    }

    public long getUniqueVisitors() {
        return uniqueVisitors;
    }

    public long getClones() {
        return clones;
    }

    public long getUniqueCloners() {
        return uniqueCloners;
    }
}
