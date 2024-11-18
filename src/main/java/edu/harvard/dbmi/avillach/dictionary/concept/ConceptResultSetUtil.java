package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.util.JsonBlobParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Component
public class ConceptResultSetUtil {

    private final JsonBlobParser jsonBlobParser;

    @Autowired
    public ConceptResultSetUtil(JsonBlobParser jsonBlobParser) {
        this.jsonBlobParser = jsonBlobParser;
    }

    public CategoricalConcept mapCategorical(ResultSet rs, boolean withMeta) throws SQLException {
        Map<String, String> metadata = null;
        if (withMeta) {
            metadata = jsonBlobParser.parseMetaData(rs.getString("metadata"));
        }

        return new CategoricalConcept(
            rs.getString("concept_path"), rs.getString("name"), rs.getString("display"), rs.getString("dataset"),
            rs.getString("description"), rs.getString("values") == null ? List.of() : jsonBlobParser.parseValues(rs.getString("values")),
            rs.getBoolean("allowFiltering"), rs.getString("studyAcronym"), null, metadata
        );
    }

    public ContinuousConcept mapContinuous(ResultSet rs, boolean withMeta) throws SQLException {
        Map<String, String> metadata = null;
        if (withMeta) {
            metadata = jsonBlobParser.parseMetaData(rs.getString("metadata"));
        }

        return new ContinuousConcept(
            rs.getString("concept_path"), rs.getString("name"), rs.getString("display"), rs.getString("dataset"),
            rs.getString("description"), rs.getBoolean("allowFiltering"), jsonBlobParser.parseMin(rs.getString("values")),
            jsonBlobParser.parseMax(rs.getString("values")), rs.getString("studyAcronym"), metadata
        );
    }

    public ContinuousConcept mapContinuous(ResultSet rs) throws SQLException {
        return mapContinuous(rs, false);
    }

    public CategoricalConcept mapCategorical(ResultSet rs) throws SQLException {
        return mapCategorical(rs, false);
    }

}
