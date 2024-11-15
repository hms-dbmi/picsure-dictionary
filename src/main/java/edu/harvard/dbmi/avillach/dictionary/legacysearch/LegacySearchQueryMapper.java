package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dictionary.facet.FilterPreProcessor;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class LegacySearchQueryMapper {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public LegacySearchQuery mapFromJson(String jsonString) throws IOException {
        JsonNode rootNode = objectMapper.readTree(jsonString);
        JsonNode queryNode = rootNode.get("query");

        String searchTerm = queryNode.get("searchTerm").asText();
        int limit = queryNode.get("limit").asInt();
        Filter filter = FilterPreProcessor.processsFilter(new Filter(List.of(), searchTerm, List.of()));
        return new LegacySearchQuery(filter, PageRequest.of(0, limit));
    }



}
