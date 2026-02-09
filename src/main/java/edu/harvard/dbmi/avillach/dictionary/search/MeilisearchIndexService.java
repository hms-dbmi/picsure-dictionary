package edu.harvard.dbmi.avillach.dictionary.search;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.Settings;
import com.meilisearch.sdk.model.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@ConditionalOnProperty(name = "search.backend", havingValue = "meilisearch")
public class MeilisearchIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(MeilisearchIndexService.class);

    private static final int BATCH_SIZE = 5000;

    private final Client client;
    private final NamedParameterJdbcTemplate template;
    private final String indexName;
    private final List<String> disallowedMetaFields;

    // @formatter:off
	private static final String CONCEPT_QUERY = """
		SELECT
			cn.concept_node_id,
			cn.dataset_id,
			cn.name,
			cn.display,
			cn.concept_path,
			cn.concept_type,
			ds.ref AS dataset_ref,
			ds.full_name AS dataset_full_name,
			ds.abbreviation AS study_acronym,
			ds.description AS dataset_description,
			parent.display AS parent_display,
			grandparent.display AS grandparent_display,
			meta_desc.value AS meta_description,
			meta_values.value AS meta_values_json,
			meta_min.value AS meta_min,
			meta_max.value AS meta_max
		FROM
			concept_node cn
			LEFT JOIN dataset ds ON cn.dataset_id = ds.dataset_id
			LEFT JOIN concept_node parent ON cn.parent_id = parent.concept_node_id
			LEFT JOIN concept_node grandparent ON parent.parent_id = grandparent.concept_node_id
			LEFT JOIN concept_node_meta meta_desc ON cn.concept_node_id = meta_desc.concept_node_id AND meta_desc.key = 'description'
			LEFT JOIN concept_node_meta meta_values ON cn.concept_node_id = meta_values.concept_node_id AND meta_values.key = 'values'
			LEFT JOIN concept_node_meta meta_min ON cn.concept_node_id = meta_min.concept_node_id AND meta_min.key = 'min'
			LEFT JOIN concept_node_meta meta_max ON cn.concept_node_id = meta_max.concept_node_id AND meta_max.key = 'max'
		WHERE
			meta_values.value IS NOT NULL AND meta_values.value <> ''
		ORDER BY cn.concept_node_id
		""";

	private static final String META_VALUES_QUERY = """
		SELECT
			cnm.concept_node_id,
			string_agg(cnm.value, ' ') AS all_meta_values
		FROM
			concept_node_meta cnm
		WHERE
			cnm.key NOT IN ('values', 'min', 'max', 'description')
		GROUP BY cnm.concept_node_id
		""";

	private static final String STIGMATIZED_QUERY = """
		SELECT
			cnm.concept_node_id
		FROM
			concept_node_meta cnm
		WHERE
			cnm.key IN (:disallowed_meta_keys) AND LOWER(cnm.value) = 'true'
		""";

	private static final String CONSENT_QUERY = """
		SELECT
			ds.dataset_id,
			ds.ref AS dataset_ref,
			c.consent_code
		FROM
			consent c
			JOIN dataset ds ON c.dataset_id = ds.dataset_id
		""";

	private static final String HARMONIZATION_QUERY = """
		SELECT
			dh.harmonized_dataset_id,
			ds.ref AS source_dataset_ref,
			c.consent_code
		FROM
			dataset_harmonization dh
			JOIN consent c ON c.dataset_id = dh.source_dataset_id
			JOIN dataset ds ON ds.dataset_id = dh.source_dataset_id
		""";

	private static final String FACET_QUERY = """
		SELECT
			fcn.concept_node_id,
			fc.name AS category_name,
			f.name AS facet_name
		FROM
			facet__concept_node fcn
			JOIN facet f ON fcn.facet_id = f.facet_id
			JOIN facet_category fc ON f.facet_category_id = fc.facet_category_id
		ORDER BY fcn.concept_node_id
		""";
	// @formatter:on

    public MeilisearchIndexService(
        Client client, NamedParameterJdbcTemplate template, @Value("${meilisearch.index-name}") String indexName,
        @Value("${filtering.unfilterable_concepts}") List<String> disallowedMetaFields
    ) {
        this.client = client;
        this.template = template;
        this.indexName = indexName;
        this.disallowedMetaFields = disallowedMetaFields;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        try {
            LOG.info("Starting Meilisearch index build for index '{}'", indexName);
            long start = System.currentTimeMillis();

            // 1. Create/update index
            TaskInfo createTask = client.createIndex(indexName, "id");
            client.waitForTask(createTask.getTaskUid());

            Index index = client.index(indexName);

            // 2. Configure index settings
            configureIndex(index);

            // 3. Load supporting data
            Map<Integer, String> metaValuesMap = loadMetaValues();
            Set<Integer> stigmatizedIds = loadStigmatizedIds();
            Map<Integer, List<String>> consentsByDatasetId = loadConsents();
            Map<Integer, Map<String, List<String>>> facetsByConceptId = loadFacets();
            Set<String> allFacetCategoryKeys = collectFacetCategoryKeys(facetsByConceptId);

            // Update filterable attributes to include dynamic facet categories
            updateFilterableAttributes(index, allFacetCategoryKeys);

            // 4. Stream concepts and build documents
            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
            int totalDocs = 0;

            List<Map<String, Object>> rows = template.queryForList(CONCEPT_QUERY, new MapSqlParameterSource());

            for (Map<String, Object> row : rows) {
                MeilisearchConceptDocument doc = buildDocument(row, metaValuesMap, stigmatizedIds, consentsByDatasetId, facetsByConceptId);
                batch.add(doc.toMap());

                if (batch.size() >= BATCH_SIZE) {
                    indexBatch(index, batch);
                    totalDocs += batch.size();
                    batch.clear();
                    LOG.info("Indexed {} documents so far...", totalDocs);
                }
            }

            // Index remaining
            if (!batch.isEmpty()) {
                indexBatch(index, batch);
                totalDocs += batch.size();
            }

            long elapsed = System.currentTimeMillis() - start;
            LOG.info("Meilisearch index build complete: {} documents indexed in {} ms", totalDocs, elapsed);
        } catch (Exception e) {
            LOG.error("Failed to build Meilisearch index", e);
            throw new RuntimeException("Meilisearch index build failed", e);
        }
    }

    private void configureIndex(Index index) throws Exception {
        Settings settings = new Settings();

        // Searchable attributes ordered by priority (weights.csv)
        settings.setSearchableAttributes(
            new String[] {"display", "conceptPath", "categoricalValues", "datasetFullName", "datasetDescription", "parentDisplay",
                "grandparentDisplay", "description", "metaValues"}
        );

        // Ranking rules: default + custom sort for allowFiltering demotion
        settings.setRankingRules(new String[] {"words", "typo", "proximity", "attribute", "sort", "exactness", "allowFiltering:desc"});

        // Sortable attributes for stable tie-breaking
        settings.setSortableAttributes(new String[] {"id", "allowFiltering"});

        TaskInfo updateTask = index.updateSettings(settings);
        client.waitForTask(updateTask.getTaskUid());
    }

    private void updateFilterableAttributes(Index index, Set<String> facetCategoryKeys) throws Exception {
        List<String> filterable = new ArrayList<>(List.of("dataset", "conceptType", "allowFiltering", "consents"));
        filterable.addAll(facetCategoryKeys);

        Settings settings = new Settings();
        settings.setFilterableAttributes(filterable.toArray(new String[0]));

        TaskInfo updateTask = index.updateSettings(settings);
        client.waitForTask(updateTask.getTaskUid());
    }

    private Map<Integer, String> loadMetaValues() {
        Map<Integer, String> map = new HashMap<>();
        template.queryForList(META_VALUES_QUERY, new MapSqlParameterSource()).forEach(row -> {
            Integer conceptNodeId = (Integer) row.get("concept_node_id");
            String allMeta = (String) row.get("all_meta_values");
            map.put(conceptNodeId, allMeta);
        });
        return map;
    }

    private Set<Integer> loadStigmatizedIds() {
        Set<Integer> ids = new HashSet<>();
        MapSqlParameterSource params = new MapSqlParameterSource("disallowed_meta_keys", disallowedMetaFields);
        template.queryForList(STIGMATIZED_QUERY, params).forEach(row -> {
            ids.add((Integer) row.get("concept_node_id"));
        });
        return ids;
    }

    private Map<Integer, List<String>> loadConsents() {
        // Load direct consents
        Map<Integer, List<String>> consentsByDatasetId = new HashMap<>();
        template.queryForList(CONSENT_QUERY, new MapSqlParameterSource()).forEach(row -> {
            Integer datasetId = (Integer) row.get("dataset_id");
            String ref = (String) row.get("dataset_ref");
            String code = (String) row.get("consent_code");
            String consentStr = (code == null || code.isEmpty()) ? ref : ref + "." + code;
            consentsByDatasetId.computeIfAbsent(datasetId, k -> new ArrayList<>()).add(consentStr);
        });

        // Load harmonized consents: harmonized datasets inherit source consents
        template.queryForList(HARMONIZATION_QUERY, new MapSqlParameterSource()).forEach(row -> {
            Integer harmonizedDatasetId = (Integer) row.get("harmonized_dataset_id");
            String sourceRef = (String) row.get("source_dataset_ref");
            String code = (String) row.get("consent_code");
            String consentStr = (code == null || code.isEmpty()) ? sourceRef : sourceRef + "." + code;
            consentsByDatasetId.computeIfAbsent(harmonizedDatasetId, k -> new ArrayList<>()).add(consentStr);
        });

        return consentsByDatasetId;
    }

    private Map<Integer, Map<String, List<String>>> loadFacets() {
        Map<Integer, Map<String, List<String>>> facetsByConceptId = new HashMap<>();
        template.queryForList(FACET_QUERY, new MapSqlParameterSource()).forEach(row -> {
            Integer conceptNodeId = (Integer) row.get("concept_node_id");
            String categoryName = (String) row.get("category_name");
            String facetName = (String) row.get("facet_name");
            String key = "facet_" + categoryName;
            facetsByConceptId.computeIfAbsent(conceptNodeId, k -> new HashMap<>()).computeIfAbsent(key, k -> new ArrayList<>())
                .add(facetName);
        });
        return facetsByConceptId;
    }

    private Set<String> collectFacetCategoryKeys(Map<Integer, Map<String, List<String>>> facetsByConceptId) {
        Set<String> keys = new HashSet<>();
        facetsByConceptId.values().forEach(m -> keys.addAll(m.keySet()));
        return keys;
    }

    private MeilisearchConceptDocument buildDocument(
        Map<String, Object> row, Map<Integer, String> metaValuesMap, Set<Integer> stigmatizedIds,
        Map<Integer, List<String>> consentsByDatasetId, Map<Integer, Map<String, List<String>>> facetsByConceptId
    ) {
        Integer conceptNodeId = (Integer) row.get("concept_node_id");
        Integer datasetId = (Integer) row.get("dataset_id");
        String datasetRef = (String) row.get("dataset_ref");

        MeilisearchConceptDocument doc = new MeilisearchConceptDocument();
        doc.setId(conceptNodeId);
        doc.setDisplay((String) row.get("display"));
        doc.setConceptPath((String) row.get("concept_path"));
        doc.setName((String) row.get("name"));
        doc.setConceptType((String) row.get("concept_type"));
        doc.setDataset(datasetRef);
        doc.setStudyAcronym((String) row.get("study_acronym"));
        doc.setDatasetFullName((String) row.get("dataset_full_name"));
        doc.setDatasetDescription((String) row.get("dataset_description"));
        doc.setParentDisplay((String) row.get("parent_display"));
        doc.setGrandparentDisplay((String) row.get("grandparent_display"));
        doc.setDescription((String) row.get("meta_description"));
        doc.setMin((String) row.get("meta_min"));
        doc.setMax((String) row.get("meta_max"));
        doc.setValuesArr((String) row.get("meta_values_json"));

        // Categorical values as searchable text
        String valuesJson = (String) row.get("meta_values_json");
        if (valuesJson != null && !valuesJson.isEmpty()) {
            // Strip JSON array syntax to make values searchable as plain text
            String searchableValues = valuesJson.replaceAll("[\\[\\]\"]", "").replace(",", " ");
            doc.setCategoricalValues(searchableValues);
        }

        // Aggregated meta values for search
        doc.setMetaValues(metaValuesMap.getOrDefault(conceptNodeId, ""));

        // Allow filtering: true unless stigmatized
        doc.setAllowFiltering(!stigmatizedIds.contains(conceptNodeId));

        // Consents: look up by dataset_id
        doc.setConsents(consentsByDatasetId.getOrDefault(datasetId, List.of()));

        // Facets
        doc.setFacets(facetsByConceptId.getOrDefault(conceptNodeId, Map.of()));

        return doc;
    }

    private void indexBatch(Index index, List<Map<String, Object>> batch) throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(batch);
        TaskInfo task = index.addDocuments(json, "id");
        client.waitForTask(task.getTaskUid());
    }
}
