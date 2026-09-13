package abhinav.projects.dev_identity.graph;

public enum EdgeType {
    /** user -> repo */
    OWNS,
    /**
     * repo -> language, weight = bytes of code in that language. Kept
     * absolute so shares can be aggregated across repos; per-repo and
     * overall percentages are derived at read time.
     */
    WRITTEN_IN,
    /** user -> user, weight = co-contribution across shared repos */
    COLLABORATES_WITH,
    /** user -> repo the user does not own */
    CONTRIBUTED_TO,
    /** user -> organization */
    MEMBER_OF
}
