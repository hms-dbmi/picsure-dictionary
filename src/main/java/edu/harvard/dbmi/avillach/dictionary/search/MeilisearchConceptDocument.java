package edu.harvard.dbmi.avillach.dictionary.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat, denormalized representation of a concept for Meilisearch indexing. Fields are derived from weights.csv priorities and the concept
 * data model.
 */
public class MeilisearchConceptDocument {

    // Primary key
    private int id;

    // Searchable fields (ordered by priority matching weights.csv)
    private String display;
    private String conceptPath;
    private String categoricalValues;
    private String parentDisplay;
    private String grandparentDisplay;
    private String description;
    private String metaValues;

    // Non-searchable fields for response mapping
    private String name;
    private String conceptType;
    private String dataset;
    private String studyAcronym;
    private String min;
    private String max;
    private String valuesArr;

    // Filterable fields
    private boolean allowFiltering;
    private List<String> consents;

    // Dynamic facet fields stored as a map; each key is "facet_<category_name>"
    // and the value is a list of facet names for that concept in that category.
    // These get flattened into the document as top-level fields during serialization.
    private Map<String, List<String>> facets = new HashMap<>();

    public MeilisearchConceptDocument() {}

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public String getConceptPath() {
        return conceptPath;
    }

    public void setConceptPath(String conceptPath) {
        this.conceptPath = conceptPath;
    }

    public String getCategoricalValues() {
        return categoricalValues;
    }

    public void setCategoricalValues(String categoricalValues) {
        this.categoricalValues = categoricalValues;
    }

    public String getParentDisplay() {
        return parentDisplay;
    }

    public void setParentDisplay(String parentDisplay) {
        this.parentDisplay = parentDisplay;
    }

    public String getGrandparentDisplay() {
        return grandparentDisplay;
    }

    public void setGrandparentDisplay(String grandparentDisplay) {
        this.grandparentDisplay = grandparentDisplay;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMetaValues() {
        return metaValues;
    }

    public void setMetaValues(String metaValues) {
        this.metaValues = metaValues;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getConceptType() {
        return conceptType;
    }

    public void setConceptType(String conceptType) {
        this.conceptType = conceptType;
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    public String getStudyAcronym() {
        return studyAcronym;
    }

    public void setStudyAcronym(String studyAcronym) {
        this.studyAcronym = studyAcronym;
    }

    public String getMin() {
        return min;
    }

    public void setMin(String min) {
        this.min = min;
    }

    public String getMax() {
        return max;
    }

    public void setMax(String max) {
        this.max = max;
    }

    public String getValuesArr() {
        return valuesArr;
    }

    public void setValuesArr(String valuesArr) {
        this.valuesArr = valuesArr;
    }

    public boolean isAllowFiltering() {
        return allowFiltering;
    }

    public void setAllowFiltering(boolean allowFiltering) {
        this.allowFiltering = allowFiltering;
    }

    public List<String> getConsents() {
        return consents;
    }

    public void setConsents(List<String> consents) {
        this.consents = consents;
    }

    public Map<String, List<String>> getFacets() {
        return facets;
    }

    public void setFacets(Map<String, List<String>> facets) {
        this.facets = facets;
    }

    /**
     * Converts this document to a map suitable for Meilisearch indexing. Dynamic facet fields are flattened into the top level.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("display", display);
        map.put("conceptPath", conceptPath);
        map.put("categoricalValues", categoricalValues != null ? categoricalValues : "");
        map.put("parentDisplay", parentDisplay != null ? parentDisplay : "");
        map.put("grandparentDisplay", grandparentDisplay != null ? grandparentDisplay : "");
        map.put("description", description != null ? description : "");
        map.put("metaValues", metaValues != null ? metaValues : "");
        map.put("name", name);
        map.put("conceptType", conceptType);
        map.put("dataset", dataset);
        map.put("studyAcronym", studyAcronym != null ? studyAcronym : "");
        map.put("min", min);
        map.put("max", max);
        map.put("valuesArr", valuesArr);
        map.put("allowFiltering", allowFiltering);
        map.put("consents", consents != null ? consents : List.of());

        // Flatten dynamic facet fields into top level
        if (facets != null) {
            for (Map.Entry<String, List<String>> entry : facets.entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }

        return map;
    }
}
