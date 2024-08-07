package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
public class FacetQueryGenerator {

    public String createFacetSQLAndPopulateParams(Filter filter, MapSqlParameterSource params) {
        Map<String, List<Facet>> groupedFacets = (filter.facets() == null ? Stream.<Facet>of() : filter.facets().stream())
            .collect(Collectors.groupingBy(Facet::category));
        if (CollectionUtils.isEmpty(filter.facets())) {
            if (StringUtils.hasLength(filter.search())) {
                return createNoFacetSQLWithSearch(filter.search(), params);
            } else {
                return createNoFacetSQLNoSearch(params);
            }
        } else if (groupedFacets.size() == 1) {
            if (StringUtils.hasLength(filter.search())) {
                return createSingleCategorySQLWithSearch(filter.facets(), filter.search(), params);
            } else {
                return createSingleCategorySQLNoSearch(filter.facets(), params);
            }
        } else {
            if (StringUtils.hasLength(filter.search())) {
                return createMultiCategorySQLWithSearch(groupedFacets, filter.search(), params);
            } else {
                return createMultiCategorySQLNoSearch(groupedFacets, params);
            }
        }
    }

    private Map<String, String> createSQLSafeCategoryKeys(List<String> categories) {
        HashMap<String, String> keys = new HashMap<>();
        for (int i = 0; i < categories.size(); i++) {
            keys.put(categories.get(i), "cat_" + i);
        }
        return keys;
    }

    private String createMultiCategorySQLWithSearch(Map<String, List<Facet>> facets, String search, MapSqlParameterSource params) {
        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(facets.keySet().stream().toList());
        params.addValue("search", search);

        /*
         For each category of facet present in the filter, create a query that represents all the concept IDs
         associated with the selected facets in that category
        */
        String conceptsQuery = "WITH " + facets.keySet().stream().map(category -> {
            List<String[]> selectedFacetsInCateory = facets.entrySet().stream()
                .filter(e -> category.equals(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .map(facet -> new String[]{facet.category(), facet.name()})
                .toList();
            params.addValue("facets_in_cat_" + categoryKeys.get(category), selectedFacetsInCateory);
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            return """
                facet_category_%s_concepts AS (
                    SELECT
                        DISTINCT(concept_node.concept_node_id) as concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                        LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                        LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                        LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    WHERE
                        (fc.name, facet.name) IN (:facets_in_cat_%s)
                        AND concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery
                        AND (
                            continuous_min.value <> '' OR
                            continuous_max.value <> '' OR
                            categorical_values.value <> ''
                        )
                )
                """.formatted(categoryKeys.get(category), categoryKeys.get(category));
        }).collect(Collectors.joining(",\n"));
        /*
           Categories with no selected facets contribute no concepts, so ignore them for now.
           Now, for each category with selected facets, take all the concepts from all other categories with selections
           and INTERSECT them. This creates the concepts for this category
         */
        String selectedFacetsQuery = facets.keySet().stream().map(category -> {
                String allConceptsForCategory = categoryKeys.values().stream()
                    .filter(key -> !categoryKeys.get(category).equals(key))
                    .map(key -> "SELECT * FROM facet_category_" + key + "_concepts")
                    .collect(Collectors.joining(" INTERSECT "));
                params.addValue("", "");
                return """
                    (
                        SELECT
                            facet.facet_id, count(*) as facet_count
                        FROM
                            facet
                            JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        WHERE
                            fcn.concept_node_id IN (%s)
                        GROUP BY
                            facet.facet_id
                        ORDER BY
                            facet_count DESC
                    )
                    """.formatted(allConceptsForCategory);
            })
            .collect(Collectors.joining("\n\tUNION\n"));

        /*
            For categories with no selected facets, take all the concepts from all facets, and use them for the counts
         */
        params.addValue("all_selected_facet_categories", facets.keySet());
        String allConceptsForUnselectedCategories = categoryKeys.values().stream()
            .map(key -> "SELECT * FROM facet_category_" + key + "_concepts")
            .collect(Collectors.joining(" INTERSECT "));
        String unselectedFacetsQuery = """
            UNION
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                WHERE
                    fc.name NOT IN (:all_selected_facet_categories)
                    AND fcn.concept_node_id IN (%s)
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(allConceptsForUnselectedCategories);

        return conceptsQuery + selectedFacetsQuery + unselectedFacetsQuery;
    }

    private String createMultiCategorySQLNoSearch(Map<String, List<Facet>> facets, MapSqlParameterSource params) {
        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(facets.keySet().stream().toList());

        /*
         For each category of facet present in the filter, create a query that represents all the concept IDs
         associated with the selected facets in that category
        */
        String conceptsQuery = "WITH " + facets.keySet().stream().map(category -> {
            List<String[]> selectedFacetsInCateory = facets.entrySet().stream()
                .filter(e -> category.equals(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .map(facet -> new String[]{facet.category(), facet.name()})
                .toList();
            params.addValue("facets_in_cat_" + categoryKeys.get(category), selectedFacetsInCateory);
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            return """
                facet_category_%s_concepts AS (
                    SELECT
                        DISTINCT(concept_node.concept_node_id) as concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                        LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                        LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                        LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    WHERE
                        (fc.name, facet.name) IN (:facets_in_cat_%s)
                        AND (
                            continuous_min.value <> '' OR
                            continuous_max.value <> '' OR
                            categorical_values.value <> ''
                        )
                )
                """.formatted(categoryKeys.get(category), categoryKeys.get(category));
        }).collect(Collectors.joining(",\n"));
        /*
           Now, for each category with selected facets, take all the concepts from all other categories with selections
           and INTERSECT them. This creates the concepts for this category
         */
        String selectedFacetsQuery = facets.keySet().stream().map(category -> {
            params.addValue("facet_category_" + categoryKeys.get(category), category);
            String allConceptsForCategory = categoryKeys.values().stream()
                .filter(key -> !categoryKeys.get(category).equals(key))
                .map(key -> "SELECT * FROM facet_category_" + key + "_concepts")
                .collect(Collectors.joining(" INTERSECT "));
            params.addValue("", "");
            return """
                (
                    SELECT
                        facet.facet_id, count(*) as facet_count
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    WHERE
                        fcn.concept_node_id IN (%s)
                        AND fc.name = :facet_category_%s
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                """.formatted(allConceptsForCategory, categoryKeys.get(category));
            })
            .collect(Collectors.joining("\n\tUNION\n"));

        /*
            For categories with no selected facets, take all the concepts from all facets, and use them for the counts
         */
        params.addValue("all_selected_facet_categories", facets.keySet());
        String allConceptsForUnselectedCategories = categoryKeys.values().stream()
            .map(key -> "SELECT * FROM facet_category_" + key + "_concepts")
            .collect(Collectors.joining(" INTERSECT "));
        String unselectedFacetsQuery = """
            UNION
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                WHERE
                    fc.name NOT IN (:all_selected_facet_categories)
                    AND fcn.concept_node_id IN (%s)
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(allConceptsForUnselectedCategories);

        return conceptsQuery + selectedFacetsQuery + unselectedFacetsQuery;
    }

    private String createSingleCategorySQLWithSearch(List<Facet> facets, String search, MapSqlParameterSource params) {
        params.addValue("facet_category_name", facets.getFirst().category());
        params.addValue("facets", facets.stream().map(Facet::name).toList());
        params.addValue("search", search);
        // return all the facets that
        //      are in the matched category
        //      are displayable
        //      match a concept with search hits
        // UNION
        // all the facets from other categories that match concepts that
        //      match selected facets from this category
        //      match search
        return """
                (
                    SELECT
                        facet.facet_id, count(*) as facet_count
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                        LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                        LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                        LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    WHERE
                        fc.name = :facet_category_name
                        AND concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery
                        AND (
                            continuous_min.value <> '' OR
                            continuous_max.value <> '' OR
                            categorical_values.value <> ''
                        )
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                UNION
                (
                    WITH matching_concepts AS (
                        SELECT
                            DISTINCT(concept_node.concept_node_id) AS concept_node_id
                        FROM
                            facet
                            JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                            JOIN concept_node ON concept_node.concept_node_id = facet__concept_node.concept_node
                            LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                            LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                            LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                        WHERE
                            fc.name = :facet_category_name
                            AND facet.name IN (:facets)
                            AND concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery
                            AND (
                                continuous_min.value <> '' OR
                                continuous_max.value <> '' OR
                                categorical_values.value <> ''
                            )
                    )
                    SELECT
                        facet.facet_id, count(*) as facet_count
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN matching_concepts ON fcn.concept_node_id = matching_concepts.concept_node_id
                    WHERE
                        fc.name <> :facet_category_name
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                """;
    }

    private String createSingleCategorySQLNoSearch(List<Facet> facets, MapSqlParameterSource params) {
        params.addValue("facet_category_name", facets.getFirst().category());
        params.addValue("facets", facets.stream().map(Facet::name).toList());
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
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                        LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                        LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                        LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    WHERE
                        fc.name = :facet_category_name
                        AND (
                            continuous_min.value <> '' OR
                            continuous_max.value <> '' OR
                            categorical_values.value <> ''
                        )
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                UNION
                (
                    WITH matching_concepts AS (
                        SELECT
                            DISTINCT(concept_node.concept_node_id) AS concept_node_id
                        FROM
                            facet
                            JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                            JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                            JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                            LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                            LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                            LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                        WHERE
                            fc.name = :facet_category_name
                            AND facet.name IN (:facets)
                            AND (
                                continuous_min.value <> '' OR
                                continuous_max.value <> '' OR
                                categorical_values.value <> ''
                            )
                    )
                    SELECT
                        facet.facet_id, count(*) as facet_count
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN matching_concepts ON fcn.concept_node_id = matching_concepts.concept_node_id
                    WHERE
                        fc.name <> :facet_category_name
                    GROUP BY
                        facet.facet_id
                    ORDER BY
                        facet_count DESC
                )
                """;
    }

    private String createNoFacetSQLWithSearch(String search, MapSqlParameterSource params) {
        // return all the facets that match concepts that
        //      match search
        //      are displayable
        params.addValue("search", search);
        return """
            SELECT
                facet.facet_id, count(*) as facet_count
            FROM
                facet
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
            WHERE
                concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery
                AND (
                    continuous_min.value <> '' OR
                    continuous_max.value <> '' OR
                    categorical_values.value <> ''
                )
            GROUP BY
                facet.facet_id
            ORDER BY
                facet_count DESC
            """;

    }

    private String createNoFacetSQLNoSearch(MapSqlParameterSource params) {
        // return all the facets that match displayable concepts
        // this is the easy one!
        return """
            SELECT
                facet.facet_id, count(*) as facet_count
            FROM
                facet
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
            WHERE
                continuous_min.value <> '' OR
                continuous_max.value <> '' OR
                categorical_values.value <> ''
            GROUP BY
                facet.facet_id
            ORDER BY
                facet_count DESC
            """;
    }
}
