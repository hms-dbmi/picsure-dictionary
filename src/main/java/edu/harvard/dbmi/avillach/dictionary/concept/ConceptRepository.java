package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.FilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.dictionary.util.MapExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ConceptRepository {

    private final NamedParameterJdbcTemplate template;

    private final ConceptRowMapper mapper;

    private final FilterQueryGenerator filterGen;

    @Autowired
    public ConceptRepository(
        NamedParameterJdbcTemplate template, ConceptRowMapper mapper, FilterQueryGenerator filterGen
    ) {
        this.template = template;
        this.mapper = mapper;
        this.filterGen = filterGen;
    }


    public List<Concept> getConcepts(Filter filter, Pageable pageable) {
        String sql = """
            SELECT
                concept_node.*,
                ds.REF as dataset,
                continuous_min.VALUE as min, continuous_max.VALUE as max,
                categorical_values.VALUE as values,
                meta_description.VALUE AS description
            FROM
                concept_node
                LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
            WHERE concept_node.concept_node_id IN (
            
            """;
        QueryParamPair filterQ = filterGen.generateFilterQuery(filter, pageable);
        sql = sql + filterQ.query() + "\n)";
        MapSqlParameterSource params = filterQ.params();

        return template.query(sql, params, mapper);
    }

    public long countConcepts(Filter filter) {
        QueryParamPair pair = filterGen.generateFilterQuery(filter, Pageable.unpaged());
        String sql = "SELECT count(*) FROM (" + pair.query() + ")";
        Long count = template.queryForObject(sql, pair.params(), Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Concept> getConcept(String dataset, String conceptPath) {
        String sql = """
            SELECT
                concept_node.*,
                ds.REF as dataset,
                continuous_min.VALUE as min, continuous_max.VALUE as max,
                categorical_values.VALUE as values,
                meta_description.VALUE AS description
            FROM
                concept_node
                LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
            WHERE
                concept_node.concept_path = :conceptPath
                AND ds.REF = :dataset
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("conceptPath", conceptPath)
            .addValue("dataset", dataset);
        return template.query(sql, params, mapper).stream().findFirst();
    }

    public Map<String, String> getConceptMeta(String dataset, String conceptPath) {
        String sql = """
            SELECT
                concept_node_meta.KEY, concept_node_meta.VALUE
            FROM
                concept_node
                LEFT JOIN concept_node_meta ON concept_node.concept_node_id = concept_node_meta.concept_node_id
                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
            WHERE
                concept_node.concept_path = :conceptPath
                AND dataset.REF = :dataset
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("conceptPath", conceptPath)
            .addValue("dataset", dataset);
        return template.query(sql, params, new MapExtractor("KEY", "VALUE"));
    }
}
