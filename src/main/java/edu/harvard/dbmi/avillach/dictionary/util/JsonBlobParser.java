package edu.harvard.dbmi.avillach.dictionary.util;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonBlobParser {

    private final static Logger log = LoggerFactory.getLogger(JsonBlobParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonBlobParser() {}

    public List<String> parseValues(String valuesArr) {
        try {
            ArrayList<String> vals = new ArrayList<>();
            JSONArray arr = new JSONArray(valuesArr);
            for (int i = 0; i < arr.length(); i++) {
                vals.add(arr.getString(i));
            }
            return vals;
        } catch (JSONException ex) {
            return List.of();
        }
    }

    public Double parseMin(String valuesArr) {
        return parseFromIndex(valuesArr, 0);
    }

    protected Double parseFromIndex(String valuesArr, int index) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            if (arr.length() != 2) {
                return 0D;
            }
            Object raw = arr.get(index);
            return switch (raw) {
                case Double d -> d;
                case Integer i -> i.doubleValue();
                case BigDecimal d -> d.doubleValue();
                case BigInteger i -> i.doubleValue();
                case String s -> Double.parseDouble(s);
                default -> 0D;
            };
        } catch (JSONException ex) {
            log.warn("Invalid json array for values: {}", valuesArr, ex);
            return 0D;
        } catch (NumberFormatException ex) {
            log.warn("Valid json array but invalid val within: {}", valuesArr, ex);
            return 0D;
        }
    }

    public Double parseMax(String valuesArr) {
        return parseFromIndex(valuesArr, 1);
    }

    public Map<String, String> parseMetaData(String jsonMetaData) {
        Map<String, String> metadata;

        try {
            List<Map<String, String>> maps = objectMapper.readValue(jsonMetaData, new TypeReference<List<Map<String, String>>>() {});
            // convert the list to a flat map
            Map<String, String> map = new HashMap<>();
            for (Map<String, String> entry : maps) {
                if (map.put(entry.get("key"), entry.get("value")) != null) {
                    throw new IllegalStateException(
                        "parseMetaData() Duplicate key found in metadata. Key: " + entry.get("key") + " Value: " + entry.get("value")
                    );
                }
            }
            metadata = map;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        return metadata;
    }
}
