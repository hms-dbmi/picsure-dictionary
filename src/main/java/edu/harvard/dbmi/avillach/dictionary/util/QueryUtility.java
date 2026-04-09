package edu.harvard.dbmi.avillach.dictionary.util;

/**
 * Shared SQL fragments used by concept and facet query generators.
 */
public class QueryUtility {

    /**
     * CTE that determines whether each concept should be filterable in the UI.
     * Concepts with disallowed meta keys set to 'true' (e.g. stigmatized) are marked as non-filterable,
     * which demotes them in search ranking.
     * Expects a :disallowed_meta_keys named parameter.
     */
    public static final String ALLOW_FILTERING_Q = """
        WITH allow_filtering AS (
            SELECT
                concept_node_meta.concept_node_id AS concept_node_id,
                (concept_node_meta.value <> 'true') AS allowFiltering
            FROM
                concept_node_meta
            WHERE
                concept_node_meta.KEY IN (:disallowed_meta_keys)
        )
        """;

    /**
     * Ranking expression for search results. Uses PostgreSQL ts_rank against the
     * pre-built tsvector column (searchable_fields) with prefix matching.
     * Expects a :search named parameter.
     */
    public static final String SEARCH_QUERY =
        "ts_rank(searchable_fields, to_tsquery('english', :search || ':*'))";

    /**
     * WHERE clause filter for full-text search. Uses the GIN-indexed searchable_fields
     * tsvector column with prefix matching via to_tsquery.
     * Expects a :search named parameter.
     */
    public static final String SEARCH_WHERE =
        "concept_node.searchable_fields @@ to_tsquery('english', :search || ':*')";
}
