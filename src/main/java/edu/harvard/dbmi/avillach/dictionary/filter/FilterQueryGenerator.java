package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FilterQueryGenerator {

    /**
     * This generates a query that will return a list of concept_node IDs for the given filter.
     * <p>
     * A filter object has a list of facets, each belonging to a category. Within a category,
     * facets are ORed together. Between categories, facets are ANDed together.
     * In SQL, this is represented as N clauses for N facets, all INTERSECTed together. Search
     * also acts as its own special facet here.
     *
     * @param filter universal filter object for the page
     * @param pageable pagination, if applicable
     * @return the query and parameters needed to get a list of concepts for the filter
     */
    public QueryParamPair generateFilterQuery(Filter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> clauses = new java.util.ArrayList<>(List.of());
        if (!CollectionUtils.isEmpty(filter.facets())) {
            clauses.addAll(createFacetFilter(filter.facets(), params));
        }
        clauses.add(createValuelessNodeFilter());


        String query = "(\n" + String.join("\n\tINTERSECT\n", clauses) + "\n)";
        String havingClause = "";
        if (StringUtils.hasText(filter.search())) {
            String searchQuery = createSearchFilter(filter.search(), params);
            query = "(" + query + "\n\tUNION \n\t" + searchQuery + ")";
            havingClause = "HAVING max(rank) > 0\n";
        }
        String superQuery = """
            WITH q AS (
                %s
            )
            SELECT concept_node_id
            FROM q
            GROUP BY concept_node_id %s
            ORDER BY max(rank) DESC
            """.formatted(query, havingClause);
        if (pageable.isPaged()) {
            superQuery = superQuery + """
                LIMIT :limit
                OFFSET :offset
                """;
            params.addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        }


        return new QueryParamPair(superQuery, params);
    }

    private String createValuelessNodeFilter() {
        // concept nodes that have no values and no min/max should not get returned by search
        return """
            SELECT
                concept_node.concept_node_id, 0 as rank
            FROM
                concept_node
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
            WHERE
                continuous_min.value <> '' OR
                continuous_max.value <> '' OR
                categorical_values.value <> ''
            """;
    }

    private String createSearchFilter(String search, MapSqlParameterSource params) {
        params.addValue("search", search);
        return """
            (
                SELECT
                    concept_node.concept_node_id AS concept_node_id,
                    ts_rank(searchable_fields, (phraseto_tsquery(:search)::text || ':*')::tsquery) as rank
                FROM
                    concept_node
                    LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                    LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                    LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                WHERE
                    concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery AND
                    (
                        continuous_min.value <> '' OR
                        continuous_max.value <> '' OR
                        categorical_values.value <> ''
                    )
            )
            """;
    }

    private List<String> createFacetFilter(List<Facet> facets, MapSqlParameterSource params) {
        return facets.stream()
            .collect(Collectors.groupingBy(Facet::category))
            .entrySet().stream()
            .map(facetsForCategory ->  {
                params
                    // The templating here is to namespace the params for each facet category in the query
                    .addValue("facets_for_category_%s".formatted(facetsForCategory.getKey()), facetsForCategory.getValue().stream().map(Facet::name).toList())
                    .addValue("category_%s".formatted(facetsForCategory.getKey()), facetsForCategory.getKey());
                return """
                (
                    SELECT
                        facet__concept_node.concept_node_id AS concept_node_id , 0 as rank
                    FROM facet
                        LEFT JOIN facet__concept_node ON facet__concept_node.facet_id = facet.facet_id
                        LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                    WHERE
                        facet.name IN (:facets_for_category_%s ) AND facet_category.name = :category_%s
                )
                """.formatted(facetsForCategory.getKey(), facetsForCategory.getKey());
            })
            .toList();
    }

}
