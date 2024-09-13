package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.type.SimpleType;

import java.util.List;

@SpringBootTest
class FilterPreProcessorTest {

    @Autowired
    private FilterPreProcessor subject;

    @Test
    void shouldProcessFilter() {
        Object processedFilter = subject.afterBodyRead(
            new Filter(List.of(), "I_love_underscores", List.of()),
            Mockito.mock(HttpInputMessage.class), Mockito.mock(MethodParameter.class),
            SimpleType.constructUnsafe(Filter.class), null
        );

        Assertions.assertEquals(new Filter(List.of(), "I/love/underscores", List.of()), processedFilter);
    }

    @Test
    void shouldNotProcessOtherBodies() {
        Object actual = subject.afterBodyRead(
            "I'm an object!",
            Mockito.mock(HttpInputMessage.class), Mockito.mock(MethodParameter.class),
            SimpleType.constructUnsafe(Filter.class), null
        );

        Assertions.assertEquals("I'm an object!", actual);
    }
}