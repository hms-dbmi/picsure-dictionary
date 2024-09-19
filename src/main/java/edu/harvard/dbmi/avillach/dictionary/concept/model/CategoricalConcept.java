package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CategoricalConcept(
    String conceptPath, String name, String display, String dataset, String description,

    List<String> values,

    @Nullable
    List<Concept> children,

    @Nullable
    Map<String, String> meta,

    @Nullable
    Concept table,

    @Nullable
    Concept study

) implements Concept {

    public CategoricalConcept(
        String conceptPath, String name, String display, String dataset, String description, List<String> values,
        @Nullable List<Concept> children, @Nullable Map<String, String> meta
    ) {
        this(conceptPath, name, display, dataset, description, values, children, meta, null, null);
    }

    public CategoricalConcept(CategoricalConcept core, Map<String, String> meta) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.description, core.values, core.children, meta);
    }

    public CategoricalConcept(CategoricalConcept core, List<Concept> children) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.description, core.values, children, core.meta);
    }

    public CategoricalConcept(String conceptPath, String dataset) {
        this(conceptPath, "", "", dataset, "", List.of(), List.of(), null);
    }


    @JsonProperty("type")
    @Override
    public ConceptType type() {
        return ConceptType.Categorical;
    }

    @Override
    public CategoricalConcept withChildren(List<Concept> children) {
        return new CategoricalConcept(this, children);
    }

    @Override
    public Concept withTable(Concept table) {
        return new CategoricalConcept(
            conceptPath, name, display, dataset, description, values, children, meta, table, study
        );
    }

    @Override
    public Concept withStudy(Concept study) {
        return new CategoricalConcept(
            conceptPath, name, display, dataset, description, values, children, meta, table, study
        );
    }

    @Override
    public boolean equals(Object object) {
        return conceptEquals(object);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conceptPath, dataset);
    }

}
