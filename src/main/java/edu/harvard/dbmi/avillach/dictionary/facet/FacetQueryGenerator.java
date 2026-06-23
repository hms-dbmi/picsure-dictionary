package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.dictionary.util.QueryUtility;
import edu.harvard.dbmi.avillach.dictionary.util.SchemaDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds dynamic SQL for facet count queries. Supports three modes: no facets selected, single category selected, and multi-category
 * selected. Each mode has search and no-search variants. Facet counts reflect how many displayable concepts match each facet, scoped by the
 * current search term and consent restrictions.
 */
@Component
public class FacetQueryGenerator {

    private final String fcnQueryable;

    private static final String CONSENT_QUERY = """
        dataset.dataset_id IN (
            SELECT
                consent.dataset_id
            FROM consent
                LEFT JOIN dataset ON dataset.dataset_id = consent.dataset_id
            WHERE
                concat(dataset.ref, '.', consent.consent_code) IN (:consents) OR
                (dataset.ref IN (:consents) AND consent.consent_code = '')
            UNION
            SELECT
                dataset_harmonization.harmonized_dataset_id
            FROM consent
                JOIN dataset_harmonization ON dataset_harmonization.source_dataset_id = consent.dataset_id
                LEFT JOIN dataset ON dataset.dataset_id = dataset_harmonization.source_dataset_id
            WHERE
                concat(dataset.ref, '.', consent.consent_code) IN (:consents) OR
                (dataset.ref IN (:consents) AND consent.consent_code = '')
        ) AND
        """;

    @Autowired
    public FacetQueryGenerator(SchemaDetector schemaDetector) {
        this.fcnQueryable = schemaDetector.fcnQueryableClause("fcn");
    }

    public String createFacetSQLAndPopulateParams(Filter filter, MapSqlParameterSource params) {
        Map<String, List<Facet>> groupedFacets =
            (filter.facets() == null ? Stream.<Facet>of() : filter.facets().stream()).collect(Collectors.groupingBy(Facet::category));
        String consentWhere = "";
        if (!CollectionUtils.isEmpty(filter.consents())) {
            params.addValue("consents", filter.consents());
            consentWhere = CONSENT_QUERY;
        }
        if (CollectionUtils.isEmpty(filter.facets())) {
            if (StringUtils.hasLength(filter.search())) {
                return createNoFacetSQLWithSearch(filter.search(), consentWhere, params);
            } else {
                return createNoFacetSQLNoSearch(params, consentWhere);
            }
        } else if (groupedFacets.size() == 1) {
            if (StringUtils.hasLength(filter.search())) {
                return createSingleCategorySQLWithSearch(filter.facets(), filter.search(), consentWhere, params);
            } else {
                return createSingleCategorySQLNoSearch(filter.facets(), consentWhere, params);
            }
        } else {
            if (StringUtils.hasLength(filter.search())) {
                return createMultiCategorySQLWithSearch(groupedFacets, filter.search(), consentWhere, params);
            } else {
                return createMultiCategorySQLNoSearch(groupedFacets, consentWhere, params);
            }
        }
    }

    /**
     * Returns independent count queries for multi-category facet requests, one per UNION block. Each query includes the shared CTEs and can
     * be executed in parallel. Returns (facet_name, category_name, facet_count) rows for merging in Java.
     */
    public List<QueryParamPair> createMultiCategoryCountBlocks(Filter filter) {
        Map<String, List<Facet>> groupedFacets =
            (filter.facets() == null ? Stream.<Facet>of() : filter.facets().stream()).collect(Collectors.groupingBy(Facet::category));
        MapSqlParameterSource params = new MapSqlParameterSource();
        String consentWhere = "";
        String consentJoins = "";
        if (!CollectionUtils.isEmpty(filter.consents())) {
            params.addValue("consents", filter.consents());
            consentWhere = CONSENT_QUERY;
            consentJoins = """
                LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""";
        }

        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(groupedFacets.keySet().stream().toList());
        boolean hasSearch = StringUtils.hasLength(filter.search());
        if (hasSearch) {
            params.addValue("search", filter.search());
        }

        // Build shared CTEs (same as existing multi-category methods)
        String conceptsQuery = groupedFacets.keySet().stream().map(category -> {
            List<String[]> selectedFacetsInCategory = groupedFacets.entrySet().stream().filter(e -> category.equals(e.getKey()))
                .flatMap(e -> e.getValue().stream()).map(facet -> new String[] {facet.category(), facet.name()}).toList();
            params.addValue("facets_in_cat_" + categoryKeys.get(category), selectedFacetsInCategory);
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            String searchJoin = hasSearch ? "JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id" : "";
            String searchWhere = hasSearch ? "AND " + QueryUtility.SEARCH_WHERE : "";
            return """
                facet_category_%s_concepts AS (
                    SELECT DISTINCT(fcn.concept_node_id) as concept_node_id
                    FROM facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        %s
                    WHERE %s
                        AND (fc.name, facet.name) IN (:facets_in_cat_%s)
                        %s
                )""".formatted(categoryKeys.get(category), searchJoin, fcnQueryable, categoryKeys.get(category), searchWhere);
        }).collect(Collectors.joining(",\n"));
        String ctePrefix = "WITH " + conceptsQuery + "\n";

        List<QueryParamPair> blocks = new ArrayList<>();

        // One block per selected category: filtered by OTHER categories' concepts
        for (String category : groupedFacets.keySet()) {
            String existsChain = categoryKeys.values().stream().filter(key -> !categoryKeys.get(category).equals(key))
                .map(key -> "EXISTS (SELECT 1 FROM facet_category_" + key + "_concepts c WHERE c.concept_node_id = fcn.concept_node_id)")
                .collect(Collectors.joining("\n                AND "));
            String block = ctePrefix + """
                SELECT facet.name AS facet_name, fc.name AS category_name, count(*) AS facet_count
                FROM facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                    %s
                WHERE %s
                    AND %s
                    %s
                    AND fc.name = :facet_category_%s
                GROUP BY facet.name, fc.name
                """.formatted(consentJoins, fcnQueryable, consentWhere, existsChain, categoryKeys.get(category));
            blocks.add(new QueryParamPair(block, params));
        }

        // One block for unselected categories: filtered by ALL categories' concepts
        params.addValue("all_selected_facet_categories", groupedFacets.keySet());
        String existsChainAll = categoryKeys.values().stream()
            .map(key -> "EXISTS (SELECT 1 FROM facet_category_" + key + "_concepts c WHERE c.concept_node_id = fcn.concept_node_id)")
            .collect(Collectors.joining("\n                AND "));
        String unselectedBlock = ctePrefix + """
            SELECT facet.name AS facet_name, fc.name AS category_name, count(*) AS facet_count
            FROM facet
                JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                %s
            WHERE %s
                AND %s
                fc.name NOT IN (:all_selected_facet_categories)
                AND %s
            GROUP BY facet.name, fc.name
            """.formatted(consentJoins, fcnQueryable, consentWhere, existsChainAll);
        blocks.add(new QueryParamPair(unselectedBlock, params));

        return blocks;
    }

    private Map<String, String> createSQLSafeCategoryKeys(List<String> categories) {
        HashMap<String, String> keys = new HashMap<>();
        for (int i = 0; i < categories.size(); i++) {
            keys.put(categories.get(i), "cat_" + i);
        }
        return keys;
    }

    private String createMultiCategorySQLWithSearch(
        Map<String, List<Facet>> facets, String search, String consentWhere, MapSqlParameterSource params
    ) {
        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(facets.keySet().stream().toList());
        params.addValue("search", search);
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";

        /*
         * For each category of facet present in the filter, create a query that represents all the concept IDs associated with the selected
         * facets in that category. Concept_node JOIN needed here for searchable_fields.
         */
        String conceptsQuery = "WITH " + facets.keySet().stream().map(category -> {
            List<String[]> selectedFacetsInCateory = facets.entrySet().stream().filter(e -> category.equals(e.getKey()))
                .flatMap(e -> e.getValue().stream()).map(facet -> new String[] {facet.category(), facet.name()}).toList();
            params.addValue("facets_in_cat_" + categoryKeys.get(category), selectedFacetsInCateory);
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            return """
                facet_category_%s_concepts AS (
                    SELECT
                        DISTINCT(fcn.concept_node_id) as concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                    WHERE
                        %s
                        AND (fc.name, facet.name) IN (:facets_in_cat_%s)
                        AND %s
                )
                """.formatted(categoryKeys.get(category), fcnQueryable, categoryKeys.get(category), QueryUtility.SEARCH_WHERE);
        }).collect(Collectors.joining(",\n"));
        /*
         * Categories with no selected facets contribute no concepts, so ignore them for now. Now, for each category with selected facets,
         * take all the concepts from all other categories with selections and INTERSECT them. This creates the concepts for this category
         */
        String selectedFacetsQuery = facets.keySet().stream().map(category -> {
            String existsChain = categoryKeys.values().stream().filter(key -> !categoryKeys.get(category).equals(key))
                .map(key -> "EXISTS (SELECT 1 FROM facet_category_" + key + "_concepts c WHERE c.concept_node_id = fcn.concept_node_id)")
                .collect(Collectors.joining("\n                    AND "));
            params.addValue("", "");
            return """
                (
                    SELECT
                        facet.facet_id, count(*) as facet_count
                    FROM
                        facet
                        LEFT JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        %s
                    WHERE
                        %s
                        AND %s
                        %s
                        AND fc.name = :facet_category_%s
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                """.formatted(consentJoins, fcnQueryable, consentWhere, existsChain, categoryKeys.get(category));
        }).collect(Collectors.joining("\n\tUNION\n"));

        /*
         * For categories with no selected facets, take all the concepts from all facets, and use them for the counts
         */
        params.addValue("all_selected_facet_categories", facets.keySet());
        String existsChainAll = categoryKeys.values().stream()
            .map(key -> "EXISTS (SELECT 1 FROM facet_category_" + key + "_concepts c WHERE c.concept_node_id = fcn.concept_node_id)")
            .collect(Collectors.joining("\n                    AND "));
        String unselectedFacetsQuery = """
            UNION
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    %s
                WHERE
                    %s
                    AND %s
                    fc.name NOT IN (:all_selected_facet_categories)
                    AND %s
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(consentJoins, fcnQueryable, consentWhere, existsChainAll);

        return conceptsQuery + selectedFacetsQuery + unselectedFacetsQuery;
    }

    private String createMultiCategorySQLNoSearch(Map<String, List<Facet>> facets, String consentWhere, MapSqlParameterSource params) {
        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(facets.keySet().stream().toList());
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";

        /*
         * For each category of facet present in the filter, create a query that represents all the concept IDs associated with the selected
         * facets in that category
         */
        String conceptsQuery = "WITH " + facets.keySet().stream().map(category -> {
            List<String[]> selectedFacetsInCateory = facets.entrySet().stream().filter(e -> category.equals(e.getKey()))
                .flatMap(e -> e.getValue().stream()).map(facet -> new String[] {facet.category(), facet.name()}).toList();
            params.addValue("facets_in_cat_" + categoryKeys.get(category), selectedFacetsInCateory);
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            return """
                facet_category_%s_concepts AS (
                    SELECT
                        DISTINCT(fcn.concept_node_id) as concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    WHERE
                        %s
                        AND (fc.name, facet.name) IN (:facets_in_cat_%s)
                )
                """.formatted(categoryKeys.get(category), fcnQueryable, categoryKeys.get(category));
        }).collect(Collectors.joining(",\n"));
        /*
         * Now, for each category with selected facets, take all the concepts from all other categories with selections and INTERSECT them.
         * This creates the concepts for this category
         */
        String selectedFacetsQuery = facets.keySet().stream().map(category -> {
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            String existsChain = categoryKeys.values().stream().filter(key -> !categoryKeys.get(category).equals(key))
                .map(key -> "EXISTS (SELECT 1 FROM facet_category_" + key + "_concepts c WHERE c.concept_node_id = fcn.concept_node_id)")
                .collect(Collectors.joining("\n                    AND "));
            params.addValue("", "");
            return """
                (
                    SELECT
                        facet.facet_id, count(*) as facet_count
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        %s
                    WHERE
                        %s
                        AND %s
                        %s
                        AND fc.name = :facet_category_%s
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                """.formatted(consentJoins, fcnQueryable, consentWhere, existsChain, categoryKeys.get(category));
        }).collect(Collectors.joining("\n\tUNION\n"));

        /*
         * For categories with no selected facets, take all the concepts from all facets, and use them for the counts
         */
        params.addValue("all_selected_facet_categories", facets.keySet());
        String existsChainAll = categoryKeys.values().stream()
            .map(key -> "EXISTS (SELECT 1 FROM facet_category_" + key + "_concepts c WHERE c.concept_node_id = fcn.concept_node_id)")
            .collect(Collectors.joining("\n                    AND "));
        String unselectedFacetsQuery = """
            UNION
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    %s
                WHERE
                    %s
                    AND %s
                    fc.name NOT IN (:all_selected_facet_categories)
                    AND %s
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(consentJoins, fcnQueryable, consentWhere, existsChainAll);

        return conceptsQuery + selectedFacetsQuery + unselectedFacetsQuery;
    }

    private String createSingleCategorySQLWithSearch(List<Facet> facets, String search, String consentWhere, MapSqlParameterSource params) {
        params.addValue("facet_category_name", facets.getFirst().category());
        params.addValue("facets", facets.stream().map(Facet::name).toList());
        params.addValue("search", search);
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";
        // Part 1: facets in the matched category that are displayable + match search
        // Part 2: facets from other categories for concepts matching selected facets + search
        return """
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                    LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                WHERE
                    %s
                    AND %s
                    fc.name = :facet_category_name
                    AND %s
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            UNION
            (
                WITH matching_concepts AS (
                    SELECT
                        DISTINCT(fcn.concept_node_id) AS concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                    WHERE
                        %s
                        AND fc.name = :facet_category_name
                        AND facet.name IN (:facets)
                        AND %s
                )
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    %s
                    JOIN matching_concepts ON fcn.concept_node_id = matching_concepts.concept_node_id
                WHERE
                    %s
                    AND %s
                    fc.name <> :facet_category_name
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(
            fcnQueryable, consentWhere, QueryUtility.SEARCH_WHERE, fcnQueryable, QueryUtility.SEARCH_WHERE, consentJoins, fcnQueryable,
            consentWhere
        );
    }

    private String createSingleCategorySQLNoSearch(List<Facet> facets, String consentWhere, MapSqlParameterSource params) {
        params.addValue("facet_category_name", facets.getFirst().category());
        params.addValue("facets", facets.stream().map(Facet::name).toList());
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";
        // return all the facets in the matched category that are displayable
        // UNION
        // all the facets from other categories that match concepts that match selected facets from this category
        return """
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    %s
                WHERE
                    %s
                    AND %s
                    fc.name = :facet_category_name
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            UNION
            (
                WITH matching_concepts AS (
                    SELECT
                        DISTINCT(fcn.concept_node_id) AS concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    WHERE
                        %s
                        AND fc.name = :facet_category_name
                        AND facet.name IN (:facets)
                )
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    %s
                    JOIN matching_concepts ON fcn.concept_node_id = matching_concepts.concept_node_id
                WHERE
                    %s
                    AND %s
                    fc.name <> :facet_category_name
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(consentJoins, fcnQueryable, consentWhere, fcnQueryable, consentJoins, fcnQueryable, consentWhere);
    }

    private String createNoFacetSQLWithSearch(String search, String consentWhere, MapSqlParameterSource params) {
        // return all the facets that match concepts that
        // match search
        // are displayable
        params.addValue("search", search);
        String datasetJoin = StringUtils.hasLength(consentWhere) ? "LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id" : "";
        return """
            SELECT
                facet.facet_id, count(*) as facet_count
            FROM
                facet
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                %s
            WHERE
                %s
                AND %s
                %s
            GROUP BY
                facet.facet_id
            ORDER BY
                facet_count DESC
            """.formatted(datasetJoin, fcnQueryable, consentWhere, QueryUtility.SEARCH_WHERE);

    }

    private String createNoFacetSQLNoSearch(MapSqlParameterSource params, String consents) {
        String consentJoins = StringUtils.hasLength(consents) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";
        String whereClause = StringUtils.hasLength(consents) ? consents.strip().replaceAll("\\s+AND\\s*$", "") : "TRUE";
        return """
            SELECT
                facet.facet_id, count(*) as facet_count
            FROM
                facet
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                %s
            WHERE
                %s
                AND %s
            GROUP BY
                facet.facet_id
            ORDER BY
                facet_count DESC
            """.formatted(consentJoins, fcnQueryable, whereClause);
    }
}
