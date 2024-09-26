package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Testcontainers
@SpringBootTest
class ConceptRepositoryTest {

    @Autowired
    ConceptRepository subject;

    @Container
    static final PostgreSQLContainer<?> databaseContainer =
        new PostgreSQLContainer<>("postgres:16")
            .withReuse(true)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql"
            );

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Test
    void shouldListAllConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "", List.of()), Pageable.unpaged());
        
        Assertions.assertEquals(29, actual.size());
    }

    @Test
    void shouldListFirstTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "", List.of()), Pageable.ofSize(2).first());
        List<? extends Record> expected = List.of(
            new ContinuousConcept("\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0, 3, "FHS", null),
            new CategoricalConcept("\\Variant Data Type\\Low coverage WGS\\", "Low coverage WGS", "Low coverage WGS", "1", "Low coverage WGS", List.of("TRUE"), true, "GIC", null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldListNextTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "", List.of()), Pageable.ofSize(2).first().next());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\phs002385\\RACEG\\", "RACEG", "RACEG", "phs002385", "Race (regrouped)", List.of("Not Reported"), true, "HCT_for_SCD", null, null),
            new CategoricalConcept("\\Variant Data Type\\Low coverage WGS\\", "Low coverage WGS", "Low coverage WGS", "1", "Low coverage WGS", List.of("TRUE"), true, "GIC", null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterConceptsByFacet() {
        List<Concept> actual =
            subject.getConcepts(new Filter(List.of(new Facet("phs000007", "", "", "", 1, null, "study_ids_dataset_ids", null)), "", List.of()), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new ContinuousConcept("\\phs000007\\pht000022\\phv00004260\\FM219\\", "phv00004260", "FM219", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0, 1, "FHS", null),
            new ContinuousConcept("\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0, 3, "FHS", null),
            new ContinuousConcept("\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY", true, 0, 5, "FHS", null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterBySearch() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "COLA", List.of()), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new ContinuousConcept("\\phs000007\\pht000022\\phv00004260\\FM219\\", "phv00004260", "FM219", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0, 1, "FHS", null),
            new ContinuousConcept("\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0, 3, "FHS", null),
            new ContinuousConcept("\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY", true, 0, 5, "FHS", null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterByBothSearchAndFacet() {
        List<Concept> actual =
            subject.getConcepts(new Filter(List.of(new Facet("phs002715", "", "", "", 1, null, "study_ids_dataset_ids", null)), "phs002715", List.of()), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\phs002715\\age\\", "AGE_CATEGORY", "age", "phs002715", "Participant's age (category)", List.of("21"), true, "NSRR CFS", null, null),
            new CategoricalConcept("\\phs002715\\nsrr_ever_smoker\\", "nsrr_ever_smoker", "nsrr_ever_smoker", "phs002715", "Smoker status", List.of("yes"), true, "NSRR CFS", null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetCount() {
        long actual = subject.countConcepts(new Filter(List.of(), "", List.of()));

        Assertions.assertEquals(29L, actual);
    }

    @Test
    void shouldGetCountWithFilter() {
        Long actual = subject.countConcepts(new Filter(List.of(new Facet("phs002715", "", "", "", 1, null, "study_ids_dataset_ids", null)), "", List.of()));
        Assertions.assertEquals(2L, actual);
    }

    @Test
    void shouldGetDetailForConcept() {
        ContinuousConcept expected =
            new ContinuousConcept("\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY", true, 0, 5, "FHS", null);
        Optional<Concept> actual = subject.getConcept("phs000007", "\\phs000007\\pht000033\\phv00008849\\D080\\");

        Assertions.assertEquals(Optional.of(expected), actual);
    }

    @Test
    void shouldGetMetaForConcept() {
        Map<String, String> actual = subject.getConceptMeta("AGE_CATEGORY", "\\phs002715\\age\\");
        Map<String, String> expected = Map.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotGetConceptThatDNE() {
        Optional<Concept> actual = subject.getConcept("invalid.invalid", "fake");
        Assertions.assertEquals(Optional.empty(), actual);

        actual = subject.getConcept("fake", "\\\\B\\\\2\\\\Z\\\\");
        Assertions.assertEquals(Optional.empty(), actual);
    }

    @Test
    void shouldGetStigmatizedConcept() {
        Optional<Concept> actual = subject.getConcept("phs002385", "\\phs002385\\TXNUM\\");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertFalse(actual.get().allowFiltering());
    }

    @Test
    void shouldGetMetaForMultipleConcepts() {
        List<Concept> concepts = List.of(
            new ContinuousConcept("\\phs000007\\pht000022\\phv00004260\\FM219\\", "", "", "phs000007", "", true, null, null, "FHS", Map.of()),
            new ContinuousConcept("\\phs000007\\pht000033\\phv00008849\\D080\\", "", "", "phs000007", "", true, null, null, "FHS", Map.of())
        );

        Map<Concept, Map<String, String>> actual = subject.getConceptMetaForConcepts(concepts);
        Map<Concept, Map<String, String>> expected = Map.of(
            new ConceptShell("\\phs000007\\pht000033\\phv00008849\\D080\\", "phs000007"), Map.of(
                "unique_identifier", "false",
                "stigmatized", "false",
                "bdc_open_access", "true",
                "values", "[0, 5]",
                "description", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY",
                "free_text", "false"
            ),
            new ConceptShell("\\phs000007\\pht000022\\phv00004260\\FM219\\", "phs000007"), Map.of(
                "unique_identifier", "false",
                "stigmatized", "false",
                "bdc_open_access", "true",
                "values", "[0, 1]",
                "description", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY",
                "free_text", "false"
            )
        );
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetTree() {
        Concept d0 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\", "1");
        Concept d1 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", "1");
        Concept d2 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\", "1");
        Concept d3 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\", "1");
        Concept d4A = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\", "1");
        Concept d4B = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified );asthma\\", "1");
        d3 = d3.withChildren(List.of(d4A, d4B));
        d2.withChildren(List.of(d3));
        d1.withChildren(List.of(d2));
        d0.withChildren(List.of(d1));

        Optional<Concept> actual = subject.getConceptTree("1", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", 3);
        Optional<Concept> expected = Optional.of(d0);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetTreeForDepthThatExceedsOntology() {
        Concept d0 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\", "1");
        Concept d1 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", "1");
        Concept d2 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\", "1");
        Concept d3 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\", "1");
        Concept d4A = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\", "1");
        Concept d4B = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified );asthma\\", "1");
        d3 = d3.withChildren(List.of(d4A, d4B));
        d2.withChildren(List.of(d3));
        d1.withChildren(List.of(d2));
        d0.withChildren(List.of(d1));

        Optional<Concept> actual = subject.getConceptTree("1", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", 30);
        Optional<Concept> expected = Optional.of(d0);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReturnEmptyTreeForDNE() {
        Optional<Concept> actual = subject.getConceptTree("1", "\\ACT Top Secret ICD-69\\", 30);
        Optional<Concept> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReturnEmptyForNegativeDepth() {
        Optional<Concept> actual = subject.getConceptTree("1", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", -1);
        Optional<Concept> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetStigmatizingConcept() {
        Optional<Concept> actual = subject.getConcept("phs002385", "\\phs002385\\TXNUM\\");
        ContinuousConcept expected = new ContinuousConcept("\\phs002385\\TXNUM\\", "TXNUM", "TXNUM", "phs002385", "Transplant Number", false, 0, 0, "HCT_for_SCD", Map.of());

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }
}