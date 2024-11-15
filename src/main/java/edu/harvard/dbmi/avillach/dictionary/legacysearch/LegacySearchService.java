package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptService;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class LegacySearchService {

    private final ConceptService conceptService;

    @Autowired
    public LegacySearchService(ConceptService conceptService) {
        this.conceptService = conceptService;
    }

    public Results getSearchResults(Filter filter, Pageable pageable) {
        return new Results(conceptService.getLegacySearchResults(filter, pageable));
    }

}
