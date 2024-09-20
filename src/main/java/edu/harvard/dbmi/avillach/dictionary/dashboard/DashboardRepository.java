package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Predicate;

@Repository
public class DashboardRepository {
    private final NamedParameterJdbcTemplate template;
    private final List<DashboardColumn> columns;
    private final Set<String> nonMetaColumns;
    private final DashboardRowResultSetExtractor extractor;

    @Autowired
    public DashboardRepository(
        NamedParameterJdbcTemplate template,
        List<DashboardColumn> columns,
        @Value("${dashboard.nonmeta-columns}")
        Set<String> nonMetaColumns, DashboardRowResultSetExtractor extractor
    ) {
        this.template = template;
        this.columns = columns;
        this.nonMetaColumns = nonMetaColumns;
        this.extractor = extractor;
    }

    private static final class ListMapExtractor implements ResultSetExtractor<List<Map<String,String>>> {

        @Override
        public List<Map<String, String>> extractData(ResultSet rs) throws SQLException, DataAccessException {
            List<Map<String, String>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                    String key = rs.getMetaData().getColumnName(i + 1);
                    row.put(key, rs.getString(key));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    public List<Map<String, String>> getHackyBDCRows() {
        String sql = """
                SELECT
                    dataset.abbreviation AS abbreviation,
                    dataset.full_name AS name,
                    consent.variable_count AS clinvars,
                    consent.participant_count AS participants,
                    consent.sample_count AS samples,
                    CASE
                        WHEN consent.consent_code <> NULL THEN concat(study_accession_meta.value, '.', consent.consent_code)
                        ELSE study_accession_meta.value
                        END
                    AS accession,
                    study_focus_meta.value AS study_focus,
                    additional_info_meta.value AS additional_info_link
                FROM
                    dataset
                    LEFT JOIN consent ON consent.dataset_id = dataset.dataset_id
                    LEFT JOIN dataset_meta AS study_focus_meta ON study_focus_meta.dataset_id = dataset.dataset_id AND study_focus_meta.KEY = 'study_focus'
                    LEFT JOIN dataset_meta AS study_accession_meta ON study_accession_meta.dataset_id = dataset.dataset_id AND study_accession_meta.KEY = 'study_accession'
                    LEFT JOIN dataset_meta AS additional_info_meta ON additional_info_meta.dataset_id = dataset.dataset_id AND additional_info_meta.KEY = 'additional_info_link'
                ORDER BY name ASC, abbreviation ASC
            """;
        return template.query(sql, new ListMapExtractor());
    }

    public List<Map<String, String>> getRows() {
        String sql = """
            SELECT
                abbreviation, full_name AS name,
                dataset_meta.KEY AS key,
                dataset_meta.VALUE AS value
            FROM
                dataset
                JOIN dataset_meta ON dataset.dataset_id = dataset_meta.dataset_id
            WHERE
                dataset_meta.KEY IN (:keys)
            ORDER BY name ASC, abbreviation ASC
            """;
        List<String> keys = columns.stream()
            .map(DashboardColumn::dataElement)
            .filter(Predicate.not(nonMetaColumns::contains))
            .toList();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("keys", keys);
        return template.query(sql, params, extractor);
    }
}
