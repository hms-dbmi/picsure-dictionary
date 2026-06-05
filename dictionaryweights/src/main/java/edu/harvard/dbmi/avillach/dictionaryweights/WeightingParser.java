package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class WeightingParser {
    private static final Logger LOG = LoggerFactory.getLogger(WeightingParser.class);
    private static final Set<String> VALID_TIERS = Set.of("A", "B", "C", "D");

    public List<Weight> parseWeights(List<String> weights) {
        return weights.stream()
            .flatMap(this::parseWeight)
            .toList();
    }

    private Stream<Weight> parseWeight(String line) {
        String[] split = line.split(",");
        if (split.length != 2) {
            LOG.warn("Failed to parse line {}, expected two column CSV", line);
            return Stream.empty();
        }

        String tier = split[1].trim().toUpperCase();
        if (!VALID_TIERS.contains(tier)) {
            LOG.warn("Failed to parse line {} because {} is not a valid tier (A/B/C/D)", line, split[1]);
            return Stream.empty();
        }

        return Stream.of(new Weight(split[0].trim(), tier));
    }

}
