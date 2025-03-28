package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

@SpringBootTest
@ActiveProfiles("test")
class FacetControllerTest {

    @MockBean
    FacetService facetService;

    @Autowired
    FacetController subject;

    @Test
    void shouldListFacets() {
        Facet questionnaire = new Facet("questionnaire", "Questionnaire", "questionnaire", "Questionnaire", 1, null, "category", null);
        Facet examination = new Facet("examination", "Examination", "examination", "Examination", 1, null, "category", null);
        FacetCategory expected = new FacetCategory("category", "Category", "categories!", List.of(questionnaire, examination));

        Filter filter =
            new Filter(List.of(new Facet("questionare", "Questionare", "?", "Examination", 1, null, "category", null)), "foo", List.of());
        Mockito.when(facetService.getFacets(filter)).thenReturn(List.of(expected));

        ResponseEntity<List<FacetCategory>> actual = subject.getFacets(filter);

        Assertions.assertEquals(List.of(expected), actual.getBody());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldGetFacetDetails() {
        Facet expected = new Facet("questionnaire", "Questionnaire", "questionnaire", "Questionare", 1, null, "category", null);
        Mockito.when(facetService.facetDetails("category", "questionnaire")).thenReturn(Optional.of(expected));

        ResponseEntity<Facet> actual = subject.facetDetails("category", "questionnaire");

        Assertions.assertEquals(expected, actual.getBody());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldNotGetFacetDetails() {
        Facet questionnaire = new Facet("questionnaire", "Questionnaire", "questionnaire", "Questionare", 1, null, "category", null);
        Mockito.when(facetService.facetDetails("category", "questionnaire")).thenReturn(Optional.of(questionnaire));

        ResponseEntity<Facet> actual = subject.facetDetails("category", "brungus");

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }
}
