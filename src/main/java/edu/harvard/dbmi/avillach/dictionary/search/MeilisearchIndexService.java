package edu.harvard.dbmi.avillach.dictionary.search;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchApiException;
import com.meilisearch.sdk.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@Service
@ConditionalOnProperty(name = "search.backend", havingValue = "meilisearch")
public class MeilisearchIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(MeilisearchIndexService.class);

    private static final int BATCH_SIZE = 1000;
    private static final int MAX_SUBMISSION_RETRIES = 5;
    private static final long RETRY_BACKOFF_BASE_MS = 1000L;
    private static final long RETRY_BACKOFF_MAX_MS = 30_000L;
    private static final String DEFAULT_DATASET_INDEX_NAME = "datasets";
    private static final String DEFAULT_DEBUG_FILE = "debug.json";

    private final Client client;
    private final NamedParameterJdbcTemplate template;
    private final String indexName;
    private final String datasetIndexName;
    private final List<String> disallowedMetaFields;
    private final String debugFile;

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
			ds.abbreviation AS study_acronym,
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
			AND cn.concept_node_id > :lastId
		ORDER BY cn.concept_node_id
		LIMIT :pageSize
		""";

	private static final String DATASET_QUERY = """
		SELECT
			ref,
			full_name,
			abbreviation,
			description
		FROM
			dataset
		""";

	private static final String META_VALUES_QUERY = """
		SELECT
			cnm.concept_node_id,
			string_agg(cnm.value, ' ') AS all_meta_values
		FROM
			concept_node_meta cnm
		WHERE
			cnm.key NOT IN ('min', 'max', 'drs_uri')
			AND cnm.concept_node_id IN (:concept_node_ids)
		GROUP BY cnm.concept_node_id
		""";

	private static final String STIGMATIZED_QUERY = """
		SELECT
			cnm.concept_node_id
		FROM
			concept_node_meta cnm
		WHERE
			cnm.key IN (:disallowed_meta_keys)
			AND LOWER(cnm.value) = 'true'
			AND cnm.concept_node_id IN (:concept_node_ids)
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
		WHERE
			fcn.concept_node_id IN (:concept_node_ids)
		ORDER BY fcn.concept_node_id
		""";

	private static final String FACET_CATEGORY_KEYS_QUERY = """
		SELECT DISTINCT
			fc.name AS category_name
		FROM
			facet_category fc
		""";
	// @formatter:on

    public MeilisearchIndexService(
        Client client, NamedParameterJdbcTemplate template, @Value("${meilisearch.index-name}") String indexName,
        @Value("${meilisearch.dataset-index-name:" + DEFAULT_DATASET_INDEX_NAME + "}") String datasetIndexName,
        @Value("${filtering.unfilterable_concepts}") List<String> disallowedMetaFields,
        @Value("${meilisearch.indexing.debug-file:" + DEFAULT_DEBUG_FILE + "}") String debugFile
    ) {
        this.client = client;
        this.template = template;
        this.indexName = indexName;
        this.datasetIndexName = datasetIndexName;
        this.disallowedMetaFields = disallowedMetaFields;
        this.debugFile = debugFile;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        try {
            LOG.info("Starting Meilisearch index build for index '{}'", indexName);
            long start = System.currentTimeMillis();

            // 1. Create/update index (enqueued; Meilisearch processes tasks in order)
            Index index = ensureIndex(indexName, "id");

            // 2. Configure index settings
            configureIndex(index);

            // 2a. Build dataset index (separate from concept index)
            try {
                buildDatasetIndex();
            } catch (Exception e) {
                LOG.error("Failed to build dataset index '{}'. Continuing without dataset index build.", datasetIndexName, e);
            }

            // 3. Load supporting data
            Map<Integer, List<String>> consentsByDatasetId = loadConsents();
            Set<String> allFacetCategoryKeys = loadFacetCategoryKeys();

            // Update filterable attributes to include dynamic facet categories
            updateFilterableAttributes(index, allFacetCategoryKeys);

            // 4. Page through concepts and build documents
            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
            int totalDocsAttempted = 0;
            int failedBatches = 0;
            int lastId = 0;
            int batchCount = 0;
            int lastTaskUid = -1;

            while (true) {
                MapSqlParameterSource pageParams = new MapSqlParameterSource();
                pageParams.addValue("lastId", lastId);
                pageParams.addValue("pageSize", BATCH_SIZE);

                List<Map<String, Object>> rows = template.queryForList(CONCEPT_QUERY, pageParams);
                if (rows.isEmpty()) break;

                List<Integer> conceptIds = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    conceptIds.add((Integer) row.get("concept_node_id"));
                }

                Map<Integer, String> metaValuesMap = loadMetaValues(conceptIds);
                Set<Integer> stigmatizedIds = loadStigmatizedIds(conceptIds);
                Map<Integer, Map<String, List<String>>> facetsByConceptId = loadFacets(conceptIds);

                for (Map<String, Object> row : rows) {
                    MeilisearchConceptDocument doc =
                        buildDocument(row, metaValuesMap, stigmatizedIds, consentsByDatasetId, facetsByConceptId);
                    batch.add(doc.toMap());
                }

                lastId = (Integer) rows.get(rows.size() - 1).get("concept_node_id");

                int taskUid = indexBatch(index, batch);
                if (taskUid >= 0) {
                    lastTaskUid = taskUid;
                } else {
                    failedBatches++;
                }
                totalDocsAttempted += batch.size();
                batch.clear();
                batchCount++;
                LOG.info("Attempted to submit {} documents so far...", totalDocsAttempted);

                // Every 5 batches, wait for the queue to drain
                if (batchCount % 5 == 0 && lastTaskUid >= 0) {
                    LOG.info("Waiting for Meilisearch to process queued tasks...");
                    awaitTask(lastTaskUid);
                }

                if (rows.size() < BATCH_SIZE) break;
            }

            long elapsed = System.currentTimeMillis() - start;
            if (failedBatches > 0) {
                LOG.warn("Meilisearch indexing skipped {} batches due to errors. See {} for details.", failedBatches, debugFile);
            }
            LOG.info("Meilisearch index build complete: {} documents attempted in {} ms", totalDocsAttempted, elapsed);
        } catch (Exception e) {
            LOG.error("Failed to build Meilisearch index. Continuing without a fresh index build.", e);
        }
    }

    private void configureIndex(Index index) throws Exception {
        Settings settings = new Settings();

        // Searchable attributes ordered by priority (weights.csv)
        settings.setSearchableAttributes(
            new String[] {"display", "conceptPath", "categoricalValues", "parentDisplay", "grandparentDisplay", "description", "metaValues"}
        );

        // Ranking rules: default + custom sort for allowFiltering demotion
        settings.setRankingRules(new String[] {"words", "typo", "proximity", "attribute", "sort", "exactness", "allowFiltering:desc"});

        // Sortable attributes for stable tie-breaking
        settings.setSortableAttributes(new String[] {"id", "allowFiltering"});

        // Raise the default 1000-hit pagination ceiling so large result sets are fully navigable
        Pagination pagination = new Pagination();
        pagination.setMaxTotalHits(100000);
        settings.setPagination(pagination);

        // Lower the minimum word length for typo tolerance so short queries like "ae" match "age"
        HashMap<String, Integer> minWordSizeForTypos = new HashMap<>();
        minWordSizeForTypos.put("oneTypo", 2);
        minWordSizeForTypos.put("twoTypos", 4);
        TypoTolerance typoTolerance = new TypoTolerance();
        typoTolerance.setMinWordSizeForTypos(minWordSizeForTypos);
        settings.setTypoTolerance(typoTolerance);

        index.updateSettings(settings);
    }

    private void buildDatasetIndex() throws Exception {
        if (datasetIndexName == null || datasetIndexName.isBlank()) {
            LOG.warn("Dataset index name is blank; skipping dataset index build.");
            return;
        }
        if (datasetIndexName.equals(indexName)) {
            LOG.warn("Dataset index name '{}' matches concept index name; skipping dataset index build.", datasetIndexName);
            return;
        }

        Index datasetIndex = ensureIndex(datasetIndexName, "ref");
        configureDatasetIndex(datasetIndex);
        indexDatasets(datasetIndex);
    }

    private void configureDatasetIndex(Index index) throws Exception {
        Settings settings = new Settings();
        settings.setSearchableAttributes(new String[] {"fullName", "description", "abbreviation", "ref"});
        index.updateSettings(settings);
    }

    private void indexDatasets(Index datasetIndex) throws Exception {
        List<Map<String, Object>> rows = template.queryForList(DATASET_QUERY, new MapSqlParameterSource());
        if (rows.isEmpty()) {
            LOG.info("No datasets found for dataset index '{}'.", datasetIndexName);
            return;
        }

        List<Map<String, Object>> docs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("ref", row.get("ref"));
            doc.put("fullName", row.get("full_name"));
            doc.put("abbreviation", row.get("abbreviation"));
            doc.put("description", row.get("description"));
            docs.add(doc);
        }

        datasetIndex.addDocuments(MAPPER.writeValueAsString(docs), "ref");
    }

    private void updateFilterableAttributes(Index index, Set<String> facetCategoryKeys) throws Exception {
        List<String> filterable = new ArrayList<>(List.of("dataset", "conceptType", "allowFiltering", "consents"));
        filterable.addAll(facetCategoryKeys);

        Settings settings = new Settings();
        settings.setFilterableAttributes(filterable.toArray(new String[0]));

        index.updateSettings(settings);
    }

    private Map<Integer, String> loadMetaValues(List<Integer> conceptIds) {
        if (conceptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, String> map = new HashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource("concept_node_ids", conceptIds);
        template.queryForList(META_VALUES_QUERY, params).forEach(row -> {
            Integer conceptNodeId = (Integer) row.get("concept_node_id");
            String allMeta = (String) row.get("all_meta_values");
            map.put(conceptNodeId, allMeta);
        });
        return map;
    }

    private Set<Integer> loadStigmatizedIds(List<Integer> conceptIds) {
        if (conceptIds.isEmpty() || disallowedMetaFields == null || disallowedMetaFields.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> ids = new HashSet<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("disallowed_meta_keys", disallowedMetaFields);
        params.addValue("concept_node_ids", conceptIds);
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

    private Map<Integer, Map<String, List<String>>> loadFacets(List<Integer> conceptIds) {
        if (conceptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, Map<String, List<String>>> facetsByConceptId = new HashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource("concept_node_ids", conceptIds);
        template.queryForList(FACET_QUERY, params).forEach(row -> {
            Integer conceptNodeId = (Integer) row.get("concept_node_id");
            String categoryName = (String) row.get("category_name");
            String facetName = (String) row.get("facet_name");
            String key = "facet_" + categoryName;
            facetsByConceptId.computeIfAbsent(conceptNodeId, k -> new HashMap<>()).computeIfAbsent(key, k -> new ArrayList<>())
                .add(facetName);
        });
        return facetsByConceptId;
    }

    private Set<String> loadFacetCategoryKeys() {
        Set<String> keys = new HashSet<>();
        template.queryForList(FACET_CATEGORY_KEYS_QUERY, new MapSqlParameterSource()).forEach(row -> {
            String categoryName = (String) row.get("category_name");
            if (categoryName != null && !categoryName.isBlank()) {
                keys.add("facet_" + categoryName);
            }
        });
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

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Index a batch. On payload_too_large (413), splits in half recursively. Base case: a single document fails to insert and is written to
     * the configured debug file. On other failures, retries with backoff then writes to the configured debug file and continues.
     */
    private int indexBatch(Index index, List<Map<String, Object>> batch) {
        if (batch.isEmpty()) {
            return -1;
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(batch);
        } catch (Exception e) {
            LOG.error("Failed to serialize batch of {} documents. Writing to {} and continuing.", batch.size(), debugFile, e);
            writeDebugFile(batch);
            return -1;
        }

        int attempts = 0;
        while (true) {
            try {
                TaskInfo task = index.addDocuments(json, "id");
                return task.getTaskUid();
            } catch (Exception e) {
                if (isPayloadTooLarge(e)) {
                    if (batch.size() == 1) {
                        LOG.error("Payload too large for single document. Writing to {} and continuing.", debugFile, e);
                        writeDebugFile(batch);
                        return -1;
                    }
                    return splitAndIndex(index, batch);
                }

                attempts++;
                if (attempts > MAX_SUBMISSION_RETRIES) {
                    LOG.error(
                        "Failed to submit batch of {} documents after {} retries. Writing to {} and continuing.", batch.size(),
                        MAX_SUBMISSION_RETRIES, debugFile, e
                    );
                    writeDebugFile(batch);
                    return -1;
                }

                long backoffMs = Math.min(RETRY_BACKOFF_MAX_MS, RETRY_BACKOFF_BASE_MS * (1L << (attempts - 1)));
                LOG.warn(
                    "Failed to submit batch of {} documents (attempt {}/{}). Retrying in {} ms: {}", batch.size(), attempts,
                    MAX_SUBMISSION_RETRIES, backoffMs, e.getMessage()
                );
                if (!sleep(backoffMs)) {
                    LOG.warn("Batch retry interrupted. Skipping batch of {} documents.", batch.size());
                    return -1;
                }
            }
        }
    }

    private int splitAndIndex(Index index, List<Map<String, Object>> batch) {
        int mid = batch.size() / 2;
        LOG.warn("Reducing payload by splitting {} documents into {} and {}.", batch.size(), mid, batch.size() - mid);
        int leftTaskUid = indexBatch(index, new ArrayList<>(batch.subList(0, mid)));
        int rightTaskUid = indexBatch(index, new ArrayList<>(batch.subList(mid, batch.size())));
        return rightTaskUid >= 0 ? rightTaskUid : leftTaskUid;
    }

    private boolean isPayloadTooLarge(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof MeilisearchApiException) {
                MeilisearchApiException apiException = (MeilisearchApiException) current;
                String code = apiException.getCode();
                if (code != null && code.contains("payload_too_large")) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && (message.contains("413") || message.contains("payload_too_large") || message.contains("size limit"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean sleep(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Index ensureIndex(String indexUid, String primaryKey) throws Exception {
        try {
            client.createIndex(indexUid, primaryKey);
        } catch (MeilisearchApiException e) {
            if (!isIndexAlreadyExists(e)) {
                throw e;
            }
            LOG.info("Meilisearch index '{}' already exists; reusing it.", indexUid);
        }
        return client.index(indexUid);
    }

    private boolean isIndexAlreadyExists(MeilisearchApiException e) {
        String code = e.getCode();
        if (code != null && code.contains("index_already_exists")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains("index_already_exists");
    }

    private synchronized void writeDebugFile(List<Map<String, Object>> batch) {
        writeBatchToFile(batch, debugFile);
    }

    private void writeBatchToFile(List<Map<String, Object>> batch, String fileName) {
        try (FileWriter fw = new FileWriter(fileName, true)) {
            fw.write(MAPPER.writeValueAsString(batch));
            fw.write(System.lineSeparator());
        } catch (IOException ex) {
            LOG.error("Failed to write batch to {}: {}", fileName, ex.getMessage());
        }
    }

    /**
     * Poll Meilisearch until the given task is processed, with a generous timeout. This lets the task queue drain before we submit more
     * batches.
     */
    private void awaitTask(int taskUid) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            try {
                Thread.sleep(1000);
                Task task = client.getTask(taskUid);
                TaskStatus status = task.getStatus();
                if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED || status == TaskStatus.CANCELED) {
                    return;
                }
            } catch (Exception e) {
                LOG.debug("Waiting for task {} (attempt {}): {}", taskUid, i, e.getMessage());
            }
        }
        LOG.warn("Timed out waiting for Meilisearch task {} after 300s", taskUid);
    }
}
