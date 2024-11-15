package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@ControllerAdvice
public class FilterPreProcessor implements RequestBodyAdvice {
    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
        HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType
    ) throws IOException {
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(
        Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) {
        if (body instanceof Filter filter) {
            return processsFilter(filter);
        }
        return body;
    }

    public static Filter processsFilter(Filter filter) {
        List<Facet> newFacets = filter.facets();
        List<String> newConsents = filter.consents();
        if (filter.facets() != null) {
            newFacets = new ArrayList<>(filter.facets());
            newFacets.sort(Comparator.comparing(Facet::name));
        }
        if (filter.consents() != null) {
            newConsents = new ArrayList<>(newConsents);
            newConsents.sort(Comparator.comparing(Function.identity()));
        }
        filter = new Filter(newFacets, filter.search(), newConsents);

        if (StringUtils.hasLength(filter.search())) {
            // filter = new Filter(filter.facets(), filter.search().replaceAll("_", "/"), filter.consents());
            filter = new Filter(filter.facets(), constructTsQuery(filter.search()), filter.consents());
        }
        return filter;
    }

    @Override
    public Object handleEmptyBody(
        Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return body;
    }

    // An attempt to provide OR search that will produce similar results to legacy search-prototype
    private String constructTsQuery(String searchTerm) {
        // Split on the | to enable or queries
        String[] orGroups = searchTerm.split("\\|");
        List<String> orClauses = new ArrayList<>();

        for (String group : orGroups) {
            // To replicate legacy search we will split using its regex [\\s\\p{Punct}]+
            String[] tokens = group.trim().split("[\\s\\p{Punct}]+");

            // Now we will combine the tokens in this group and '&' them together.
            String andClause = Arrays.stream(tokens).filter(token -> !token.isBlank()) // remove empty tokens.
                .map(token -> token + ":*") // add the wild card for search
                .collect(Collectors.joining(" & "));

            if (!andClause.isBlank()) {
                orClauses.add(andClause);
            }
        }


        return String.join(" | ", orClauses);
    }
}
