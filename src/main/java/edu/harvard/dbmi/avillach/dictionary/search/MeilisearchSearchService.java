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
    private static final int DATASET_SEARCH_PAGE_SIZE = 1000;
    private static final int ID_FETCH_PAGE_SIZE = 1000;
    private static final int DATASET_ONLY_CHUNK_SIZE = 1000;

    private final Client client;
    private final String indexName;
    private final String datasetIndexName;
    private final JsonBlobParser jsonBlobParser;

    public MeilisearchSearchService(
        Client client, @Value("${meilisearch.index-name}") String indexName,
        @Value("${meilisearch.dataset-index-name:datasets}") String datasetIndexName, JsonBlobParser jsonBlobParser
    ) {
        this.client = client;
        this.indexName = indexName;
        this.datasetIndexName = datasetIndexName;
        this.jsonBlobParser = jsonBlobParser;
    }

    public List<Concept> searchConcepts(Filter filter, Pageable pageable) {
        String query = normalizeQuery(filter);
        try {
            Index index = client.index(indexName);
            if (query.isEmpty()) {
                SearchRequest request = buildSearchRequest(query, filter, pageable, null);
                Searchable result = index.search(request);
                return mapHitsToConcepts(result.getHits());
            }

            SearchResultPaginated conceptResult = searchPaginated(index, buildSearchRequest(query, filter, pageable, null));
            List<HashMap<String, Object>> conceptHits =
                conceptResult != null && conceptResult.getHits() != null ? conceptResult.getHits() : List.of();
            long conceptTotal = conceptResult != null ? conceptResult.getTotalHits() : 0L;

            List<String> datasetRefs = searchMatchingDatasets(query);
            if (datasetRefs.isEmpty()) {
                return mapHitsToConcepts(conceptHits);
            }

            int pageSize = pageable.getPageSize();
            int offset = pageable.getPageNumber() * pageSize;
            if (offset + pageSize <= conceptTotal) {
                return mapHitsToConcepts(conceptHits);
            }

            List<Concept> results = new ArrayList<>(pageSize);
            results.addAll(mapHitsToConcepts(conceptHits));

            int remaining = pageSize - results.size();
            if (remaining <= 0) {
                return results;
            }

            int datasetOffset = (int) Math.max(0, offset - conceptTotal);
            Set<Integer> overlapIds = fetchOverlapIds(index, query, filter, datasetRefs);
            List<HashMap<String, Object>> datasetHits =
                fetchDatasetOnlyHits(index, filter, datasetRefs, datasetOffset, remaining, overlapIds);
            results.addAll(mapHitsToConcepts(datasetHits));

            return results;
        } catch (Exception e) {
            LOG.error("Meilisearch search failed, filter={}", filter, e);
            return List.of();
        }
    }

    public long countConcepts(Filter filter) {
        String query = normalizeQuery(filter);
        try {
            Index index = client.index(indexName);
            SearchResultPaginated conceptResult = searchPaginated(index, buildSearchRequest(query, filter, Pageable.unpaged(), null));
            long conceptTotal = conceptResult != null ? conceptResult.getTotalHits() : 0L;
            if (query.isEmpty()) {
                return conceptTotal;
            }

            List<String> datasetRefs = searchMatchingDatasets(query);
            if (datasetRefs.isEmpty()) {
                return conceptTotal;
            }

            long datasetTotal = getTotalHits(index, buildSearchRequest("", filter, Pageable.unpaged(), datasetRefs));
            long overlapTotal = getTotalHits(index, buildSearchRequest(query, filter, Pageable.unpaged(), datasetRefs));

            return conceptTotal + datasetTotal - overlapTotal;
        } catch (Exception e) {
            LOG.error("Meilisearch count failed, filter={}", filter, e);
            return 0;
        }
    }

    private SearchRequest buildSearchRequest(String query, Filter filter, Pageable pageable, List<String> datasetRefs) {
        SearchRequest request = buildBaseSearchRequest(query, filter, datasetRefs);
        applyPagination(request, pageable);
        return request;
    }

    private SearchRequest buildBaseSearchRequest(String query, Filter filter, List<String> datasetRefs) {
        SearchRequest request = new SearchRequest(query != null ? query.trim() : "");

        // Build filter expressions
        String[] filterExpressions = buildFilterExpressions(filter, datasetRefs);
        if (filterExpressions.length > 0) {
            request.setFilter(filterExpressions);
        }

        // Sort by allowFiltering descending so stigmatized concepts appear last
        request.setSort(new String[] {"allowFiltering:desc", "id:asc"});

        return request;
    }

    private void applyPagination(SearchRequest request, Pageable pageable) {
        // Use page/hitsPerPage mode to get exact totalHits instead of estimatedTotalHits
        if (pageable.isPaged()) {
            request.setPage(pageable.getPageNumber() + 1); // Meilisearch pages are 1-based
            request.setHitsPerPage(pageable.getPageSize());
        } else {
            request.setPage(1);
            request.setHitsPerPage(0);
        }
    }

    /**
     * Builds filter expression array. Each element in the array is ANDed together by Meilisearch. Within facet categories, values are ORed
     * using Meilisearch filter syntax.
     */
    private String[] buildFilterExpressions(Filter filter, List<String> datasetRefs) {
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

        if (!CollectionUtils.isEmpty(datasetRefs)) {
            String datasetClause =
                datasetRefs.stream().map(ref -> "dataset = \"" + escapeMeilisearchValue(ref) + "\"").collect(Collectors.joining(" OR "));
            expressions.add("(" + datasetClause + ")");
        }

        return expressions.toArray(new String[0]);
    }

    private String escapeMeilisearchValue(String value) {
        // Escape double quotes in filter values
        return value.replace("\"", "\\\"");
    }

    private String normalizeQuery(Filter filter) {
        if (filter == null || filter.search() == null) {
            return "";
        }
        return filter.search().trim();
    }

    private SearchResultPaginated searchPaginated(Index index, SearchRequest request) throws Exception {
        Searchable result = index.search(request);
        if (result instanceof SearchResultPaginated paginated) {
            return paginated;
        }
        return null;
    }

    private long getTotalHits(Index index, SearchRequest request) throws Exception {
        Searchable result = index.search(request);
        if (result instanceof SearchResultPaginated paginated) {
            return paginated.getTotalHits();
        }
        return result.getHits() != null ? result.getHits().size() : 0;
    }

    private List<Concept> mapHitsToConcepts(List<HashMap<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream().map(this::mapHitToConcept).toList();
    }

    private List<String> searchMatchingDatasets(String query) {
        if (query == null || query.isBlank() || datasetIndexName == null || datasetIndexName.isBlank()) {
            return List.of();
        }
        if (datasetIndexName.equals(indexName)) {
            LOG.warn("Dataset index name '{}' matches concept index name; skipping dataset lookup.", datasetIndexName);
            return List.of();
        }
        try {
            Index datasetIndex = client.index(datasetIndexName);
            Set<String> refs = new LinkedHashSet<>();
            int offset = 0;

            while (true) {
                SearchRequest request = new SearchRequest(query);
                request.setOffset(offset);
                request.setLimit(DATASET_SEARCH_PAGE_SIZE);
                request.setAttributesToRetrieve(new String[] {"ref"});

                Searchable result = datasetIndex.search(request);
                List<HashMap<String, Object>> hits = result.getHits();
                if (hits == null || hits.isEmpty()) {
                    break;
                }

                for (HashMap<String, Object> hit : hits) {
                    String ref = getStringField(hit, "ref");
                    if (!ref.isBlank()) {
                        refs.add(ref);
                    }
                }

                if (hits.size() < DATASET_SEARCH_PAGE_SIZE) {
                    break;
                }
                offset += hits.size();
            }

            return new ArrayList<>(refs);
        } catch (Exception e) {
            LOG.error("Meilisearch dataset search failed, query='{}'", query, e);
            return List.of();
        }
    }

    private Set<Integer> fetchOverlapIds(Index index, String query, Filter filter, List<String> datasetRefs) throws Exception {
        if (datasetRefs == null || datasetRefs.isEmpty()) {
            return Set.of();
        }

        SearchRequest request = buildBaseSearchRequest(query, filter, datasetRefs);
        request.setAttributesToRetrieve(new String[] {"id"});
        request.setLimit(ID_FETCH_PAGE_SIZE);

        Set<Integer> ids = new HashSet<>();
        int offset = 0;
        while (true) {
            request.setOffset(offset);
            Searchable result = index.search(request);
            List<HashMap<String, Object>> hits = result.getHits();
            if (hits == null || hits.isEmpty()) {
                break;
            }

            for (HashMap<String, Object> hit : hits) {
                Integer id = getIntField(hit, "id");
                if (id != null) {
                    ids.add(id);
                }
            }

            if (hits.size() < ID_FETCH_PAGE_SIZE) {
                break;
            }
            offset += hits.size();
        }

        return ids;
    }

    private List<HashMap<String, Object>> fetchDatasetOnlyHits(
        Index index, Filter filter, List<String> datasetRefs, int datasetOffset, int limit, Set<Integer> excludedIds
    ) throws Exception {
        if (limit <= 0 || datasetRefs == null || datasetRefs.isEmpty()) {
            return List.of();
        }

        if (excludedIds == null) {
            excludedIds = Set.of();
        }

        SearchRequest request = buildBaseSearchRequest("", filter, datasetRefs);
        if (excludedIds.isEmpty()) {
            request.setOffset(datasetOffset);
            request.setLimit(limit);
            Searchable result = index.search(request);
            List<HashMap<String, Object>> hits = result.getHits();
            return hits != null ? hits : List.of();
        }

        int rawOffset = 0;
        int skipped = 0;
        int chunkSize = Math.max(DATASET_ONLY_CHUNK_SIZE, limit);
        List<HashMap<String, Object>> results = new ArrayList<>(limit);
        request.setLimit(chunkSize);

        while (results.size() < limit) {
            request.setOffset(rawOffset);
            Searchable result = index.search(request);
            List<HashMap<String, Object>> hits = result.getHits();
            if (hits == null || hits.isEmpty()) {
                break;
            }

            for (HashMap<String, Object> hit : hits) {
                Integer id = getIntField(hit, "id");
                if (id != null && excludedIds.contains(id)) {
                    continue;
                }
                if (skipped < datasetOffset) {
                    skipped++;
                    continue;
                }
                results.add(hit);
                if (results.size() >= limit) {
                    break;
                }
            }

            rawOffset += hits.size();
            if (hits.size() < chunkSize) {
                break;
            }
        }

        return results;
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

    private Integer getIntField(Map<String, Object> hit, String key) {
        Object value = hit.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean getBooleanField(Map<String, Object> hit, String key) {
        Object value = hit.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }
}
