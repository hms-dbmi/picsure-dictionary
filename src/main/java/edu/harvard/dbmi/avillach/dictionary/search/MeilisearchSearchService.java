package edu.harvard.dbmi.avillach.dictionary.search;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.Searchable;
import com.meilisearch.sdk.model.SearchResultPaginated;
import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptType;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.util.JsonBlobParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "search.backend", havingValue = "meilisearch")
public class MeilisearchSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(MeilisearchSearchService.class);

    private final Client client;
    private final String indexName;
    private final JsonBlobParser jsonBlobParser;

    public MeilisearchSearchService(Client client, @Value("${meilisearch.index-name}") String indexName, JsonBlobParser jsonBlobParser) {
        this.client = client;
        this.indexName = indexName;
        this.jsonBlobParser = jsonBlobParser;
    }

    public List<Concept> searchConcepts(Filter filter, Pageable pageable) {
        try {
            Index index = client.index(indexName);
            SearchRequest request = buildSearchRequest(filter, pageable);
            Searchable result = index.search(request);

            ArrayList<HashMap<String, Object>> hits = result.getHits();
            if (hits == null) {
                return List.of();
            }

            return hits.stream().map(this::mapHitToConcept).toList();
        } catch (Exception e) {
            LOG.error("Meilisearch search failed, filter={}", filter, e);
            return List.of();
        }
    }

    public long countConcepts(Filter filter) {
        try {
            Index index = client.index(indexName);
            SearchRequest request = buildSearchRequest(filter, Pageable.unpaged());
            Searchable result = index.search(request);
            if (result instanceof SearchResultPaginated paginated) {
                return paginated.getTotalHits();
            }
            return result.getHits() != null ? result.getHits().size() : 0;
        } catch (Exception e) {
            LOG.error("Meilisearch count failed, filter={}", filter, e);
            return 0;
        }
    }

    private SearchRequest buildSearchRequest(Filter filter, Pageable pageable) {
        SearchRequest request = new SearchRequest(filter.search() != null ? filter.search().trim() : "");

        // Build filter expressions
        String[] filterExpressions = buildFilterExpressions(filter);
        if (filterExpressions.length > 0) {
            request.setFilter(filterExpressions);
        }

        // Use page/hitsPerPage mode to get exact totalHits instead of estimatedTotalHits
        if (pageable.isPaged()) {
            request.setPage(pageable.getPageNumber() + 1); // Meilisearch pages are 1-based
            request.setHitsPerPage(pageable.getPageSize());
        } else {
            request.setPage(1);
            request.setHitsPerPage(0);
        }

        // Sort by allowFiltering descending so stigmatized concepts appear last
        request.setSort(new String[] {"allowFiltering:desc", "id:asc"});

        return request;
    }

    /**
     * Builds filter expression array. Each element in the array is ANDed together by Meilisearch. Within facet categories, values are ORed
     * using Meilisearch filter syntax.
     */
    private String[] buildFilterExpressions(Filter filter) {
        List<String> expressions = new ArrayList<>();

        // Facet filters: within a category = OR, between categories = AND
        if (!CollectionUtils.isEmpty(filter.facets())) {
            Map<String, List<Facet>> byCategory = filter.facets().stream().collect(Collectors.groupingBy(Facet::category));
            for (Map.Entry<String, List<Facet>> entry : byCategory.entrySet()) {
                String key = "facet_" + entry.getKey();
                String orClause = entry.getValue().stream().map(f -> key + " = \"" + escapeMeilisearchValue(f.name()) + "\"")
                    .collect(Collectors.joining(" OR "));
                expressions.add("(" + orClause + ")");
            }
        }

        // Consent filters
        if (!CollectionUtils.isEmpty(filter.consents())) {
            String consentClause =
                filter.consents().stream().map(c -> "consents = \"" + escapeMeilisearchValue(c) + "\"").collect(Collectors.joining(" OR "));
            expressions.add("(" + consentClause + ")");
        }

        return expressions.toArray(new String[0]);
    }

    private String escapeMeilisearchValue(String value) {
        // Escape double quotes in filter values
        return value.replace("\"", "\\\"");
    }

    @SuppressWarnings("unchecked")
    private Concept mapHitToConcept(HashMap<String, Object> hit) {
        String conceptPath = getStringField(hit, "conceptPath");
        String name = getStringField(hit, "name");
        String display = getStringField(hit, "display");
        String dataset = getStringField(hit, "dataset");
        String description = getStringField(hit, "description");
        boolean allowFiltering = getBooleanField(hit, "allowFiltering");
        String studyAcronym = getStringField(hit, "studyAcronym");
        String conceptTypeStr = getStringField(hit, "conceptType");

        ConceptType conceptType = ConceptType.toConcept(conceptTypeStr);

        return switch (conceptType) {
            case Categorical -> {
                String valuesArr = getStringField(hit, "valuesArr");
                List<String> values = (valuesArr != null && !valuesArr.isEmpty()) ? jsonBlobParser.parseValues(valuesArr) : List.of();
                yield new CategoricalConcept(
                    conceptPath, name, display, dataset, description, values, allowFiltering, studyAcronym, null, null
                );
            }
            case Continuous -> {
                String valuesArr = getStringField(hit, "valuesArr");
                Double min = null;
                Double max = null;
                if (valuesArr != null && !valuesArr.isEmpty()) {
                    min = jsonBlobParser.parseMin(valuesArr);
                    max = jsonBlobParser.parseMax(valuesArr);
                } else {
                    String minStr = getStringField(hit, "min");
                    String maxStr = getStringField(hit, "max");
                    if (minStr != null) {
                        try {
                            min = Double.parseDouble(minStr);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (maxStr != null) {
                        try {
                            max = Double.parseDouble(maxStr);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                yield new ContinuousConcept(
                    conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, Map.of()
                );
            }
        };
    }

    private String getStringField(Map<String, Object> hit, String key) {
        Object value = hit.get(key);
        return value != null ? value.toString() : "";
    }

    private boolean getBooleanField(Map<String, Object> hit, String key) {
        Object value = hit.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }
}
