package abhinav.projects.dev_identity.graph;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EdgeRepository extends JpaRepository<GraphEdge, Long> {

    Optional<GraphEdge> findBySourceIdAndTargetIdAndType(Long sourceId, Long targetId, EdgeType type);

    @Query("select e from GraphEdge e where e.source.id = :nodeId or e.target.id = :nodeId")
    List<GraphEdge> findAllTouching(@Param("nodeId") Long nodeId);
}
