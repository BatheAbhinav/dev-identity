package abhinav.projects.dev_identity.graph;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRepository extends JpaRepository<GraphNode, Long> {

    Optional<GraphNode> findByExternalKey(String externalKey);
}
