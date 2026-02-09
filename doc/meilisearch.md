# Meilisearch Integration

The dictionary API supports an optional Meilisearch backend for the `POST /concepts` search endpoint. When enabled, Meilisearch replaces PostgreSQL's tsvector full-text search with typo-tolerant, relevance-tuned search. All other endpoints (concept detail, concept tree, facets, etc.) continue to use PostgreSQL regardless of the search backend setting.

## Configuration

### Properties

| Property | Description | Default |
|----------|-------------|---------|
| `search.backend` | Search backend to use: `postgres` or `meilisearch` | `postgres` |
| `meilisearch.url` | Meilisearch server URL | `http://localhost:7700` |
| `meilisearch.api-key` | Meilisearch API key (master key or search key) | _(empty)_ |
| `meilisearch.index-name` | Name of the Meilisearch index | `concepts` |

When `search.backend=postgres` (the default), no Meilisearch beans are created and the application behaves exactly as before. No Meilisearch instance is required.

### Profile-specific configuration

**Default** (`application.properties`):

```properties
search.backend=postgres
meilisearch.url=http://localhost:7700
meilisearch.api-key=
meilisearch.index-name=concepts
```

**BDC production** (`application-bdc.properties`) reads from environment variables:

```properties
search.backend=${SEARCH_BACKEND:postgres}
meilisearch.url=${MEILISEARCH_URL:http://meilisearch:7700}
meilisearch.api-key=${MEILISEARCH_API_KEY:}
meilisearch.index-name=concepts
```

**BDC dev** (`application-bdc-dev.properties`) uses localhost defaults.

### Environment variables (production)

| Variable | Description |
|----------|-------------|
| `SEARCH_BACKEND` | Set to `meilisearch` to enable |
| `MEILISEARCH_URL` | URL of the Meilisearch instance |
| `MEILISEARCH_API_KEY` | API key for authentication |
| `MEILISEARCH_MASTER_KEY` | Master key for the Meilisearch container itself |

## Architecture

All Meilisearch-specific code lives in the `edu.harvard.dbmi.avillach.dictionary.search` package. Every bean in this package is annotated with `@ConditionalOnProperty(name = "search.backend", havingValue = "meilisearch")`, so none of them are instantiated when using the PostgreSQL backend.

### Components

```
search/
  MeilisearchConfig.java           -- Client bean configuration
  MeilisearchConceptDocument.java  -- Document model for indexing
  MeilisearchIndexService.java     -- Startup data loader (PostgreSQL -> Meilisearch)
  MeilisearchSearchService.java    -- Filter translation and result mapping
```

**MeilisearchConfig** creates the Meilisearch `Client` bean from the configured URL and API key.

**MeilisearchConceptDocument** is a flat, denormalized representation of a concept used for indexing. It holds all searchable fields, filterable fields, and response-mapping fields. The `toMap()` method serializes the document for Meilisearch, flattening dynamic facet fields (e.g., `facet_study_type`) into top-level keys.

**MeilisearchIndexService** runs on `ApplicationReadyEvent` and performs a one-time full index build:
1. Creates the index and configures settings (searchable attributes, ranking rules, filterable attributes).
2. Loads supporting data from PostgreSQL: metadata values, stigmatized concept IDs, consents (including harmonized datasets), and facet mappings.
3. Queries all `Categorical` and `Continuous` concepts from PostgreSQL.
4. Builds `MeilisearchConceptDocument` objects and batch-indexes them (5000 per batch).

**MeilisearchSearchService** translates the `Filter` DTO into a Meilisearch `SearchRequest` and maps results back to `Concept` records. It provides two methods consumed by `ConceptService`:
- `searchConcepts(Filter, Pageable)` -- returns a page of matching concepts.
- `countConcepts(Filter)` -- returns the estimated total hit count.

### Backend toggle in ConceptService

`ConceptService` accepts an optional `MeilisearchSearchService` via `@Autowired(required = false)`. When `search.backend=meilisearch`, the `listConcepts`, `listDetailedConcepts`, and `countConcepts` methods delegate to Meilisearch. All other methods (`conceptDetail`, `conceptTree`, `conceptHierarchy`, etc.) always use PostgreSQL.

## Index schema

### Document fields

| Field | Type | Searchable | Filterable | Purpose |
|-------|------|:----------:|:----------:|---------|
| `id` | int | - | - | Primary key (`concept_node_id`) |
| `display` | string | rank 1 | - | Concept display name |
| `conceptPath` | string | rank 2 | - | Full concept path |
| `categoricalValues` | string | rank 3 | - | Categorical values as plain text |
| `datasetFullName` | string | rank 4 | - | Dataset full name |
| `datasetDescription` | string | rank 5 | - | Dataset description |
| `parentDisplay` | string | rank 6 | - | Parent concept display name |
| `grandparentDisplay` | string | rank 7 | - | Grandparent concept display name |
| `description` | string | rank 8 | - | Concept description from metadata |
| `metaValues` | string | rank 9 | - | All other metadata values concatenated |
| `name` | string | - | - | Concept name (path segment) |
| `conceptType` | string | - | yes | `Categorical` or `Continuous` |
| `dataset` | string | - | yes | Dataset ref |
| `studyAcronym` | string | - | - | Dataset abbreviation |
| `min` | string | - | - | Min value (continuous concepts) |
| `max` | string | - | - | Max value (continuous concepts) |
| `valuesArr` | string | - | - | Raw JSON array of values |
| `allowFiltering` | boolean | - | yes | `false` for stigmatized concepts |
| `consents` | string[] | - | yes | Pre-computed consent strings (`"ref.code"`) |
| `facet_<category>` | string[] | - | yes | Dynamic per-category facet values |

### Searchable attribute priority

The searchable attribute order determines the `attribute` ranking rule priority. It mirrors the weights defined in `dictionaryweights/weights.csv`:

| Priority | Attribute | weights.csv source | Weight |
|:--------:|-----------|-------------------|:------:|
| 1 | `display` | `concept_node.DISPLAY` | 2 |
| 2 | `conceptPath` | `concept_node.CONCEPT_PATH` | 2 |
| 3 | `categoricalValues` | _(boosted in PostgreSQL impl)_ | - |
| 4 | `datasetFullName` | `dataset.FULL_NAME` | 1 |
| 5 | `datasetDescription` | `dataset.DESCRIPTION` | 1 |
| 6 | `parentDisplay` | `parent.DISPLAY` | 1 |
| 7 | `grandparentDisplay` | `grandparent.DISPLAY` | 1 |
| 8 | `description` | `concept_node_meta_str` | 1 |
| 9 | `metaValues` | `concept_node_meta_str` | 1 |

### Ranking rules

```
words > typo > proximity > attribute > sort > exactness > allowFiltering:desc
```

The custom `allowFiltering:desc` rule at the end demotes stigmatized concepts (where `allowFiltering = false`) to the bottom of results, matching the behavior of the PostgreSQL `rank_adjustment` multiplier.

### Sortable attributes

`id` (stable tie-breaking) and `allowFiltering` (stigmatized demotion).

## Filter translation

The `Filter` record has three fields: `facets`, `search`, and `consents`. Each maps to Meilisearch as follows:

### Search

The `search` string becomes the `q` parameter of the Meilisearch `SearchRequest`. An empty or null search matches all documents.

### Facets

Facets are grouped by category. Within a category, values are ORed; between categories, they are ANDed. This matches the PostgreSQL `INTERSECT`-based behavior.

Example filter:
```json
{
  "facets": [
    {"name": "Longitudinal", "category": "study_type"},
    {"name": "Cross-Sectional", "category": "study_type"},
    {"name": "Genomics", "category": "data_type"}
  ]
}
```

Translates to the Meilisearch filter array:
```
[
  "(facet_study_type = \"Longitudinal\" OR facet_study_type = \"Cross-Sectional\")",
  "(facet_data_type = \"Genomics\")"
]
```

Each element of the `String[]` filter is implicitly ANDed by Meilisearch.

### Consents

Consent strings are ORed together into a single filter expression:

```
(consents = "phs000123.c1" OR consents = "phs000456.c2")
```

Consent strings in the index are pre-computed at index time, including harmonized dataset mappings. A concept's dataset inherits consent strings from any source datasets linked via `dataset_harmonization`.

### Combined

When all three filter types are present, each becomes a separate element in the filter array, all ANDed together:

```
[
  "(facet_study_type = \"Longitudinal\")",
  "(facet_data_type = \"Genomics\")",
  "(consents = \"phs000123.c1\" OR consents = \"phs000456.c2\")"
]
```

## Docker Compose

Both `docker-compose.dev.yml` and `docker-compose.yml` include a Meilisearch service. The container is always present but the application only connects to it when `search.backend=meilisearch`.

### Development

```yaml
meilisearch:
  container_name: meilisearch
  image: getmeili/meilisearch:v1.12
  ports:
    - "7700:7700"
  environment:
    - MEILI_ENV=development
  volumes:
    - meili-data:/meili_data
  networks:
    - dictionary
```

In development mode, Meilisearch runs without authentication and exposes a web dashboard at `http://localhost:7700`.

### Production

```yaml
meilisearch:
  container_name: meilisearch
  image: getmeili/meilisearch:v1.12
  environment:
    - MEILI_MASTER_KEY=${MEILISEARCH_MASTER_KEY}
    - MEILI_ENV=production
  volumes:
    - meili-data:/meili_data
  restart: always
  networks:
    - dictionary
```

In production mode, `MEILI_MASTER_KEY` is required. The port is not exposed externally; only the `dictionary` network can reach it.

## Local development

1. Start the infrastructure:
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```

2. Set the search backend to `meilisearch` in your properties or environment:
   ```properties
   search.backend=meilisearch
   ```

3. Start the application. On startup, `MeilisearchIndexService` will:
   - Create the `concepts` index in Meilisearch.
   - Load all concept data from PostgreSQL and index it.
   - Log progress and completion time.

4. Browse the Meilisearch dashboard at `http://localhost:7700` to inspect the index.

5. Use the API as usual -- `POST /concepts` with a `Filter` body will now be served by Meilisearch.

To switch back to PostgreSQL, set `search.backend=postgres` (or remove the property) and restart.

## Startup behavior

When `search.backend=meilisearch`, the index is rebuilt from scratch on every application start. Because the database is read-only at runtime, a full re-index on startup ensures the Meilisearch index is always consistent with PostgreSQL. The previous index contents (if any) are replaced.

Index build time depends on the number of concepts. Progress is logged:

```
Starting Meilisearch index build for index 'concepts'
Indexed 5000 documents so far...
Indexed 10000 documents so far...
Meilisearch index build complete: 12345 documents indexed in 8432 ms
```

If the index build fails (e.g., Meilisearch is unreachable), the application will fail to start.

## Dependency

The Meilisearch Java SDK is included in `pom.xml`:

```xml
<dependency>
    <groupId>com.meilisearch.sdk</groupId>
    <artifactId>meilisearch-java</artifactId>
    <version>0.14.2</version>
</dependency>
```

This dependency is always present at compile time but the Meilisearch beans are only instantiated when `search.backend=meilisearch`.
