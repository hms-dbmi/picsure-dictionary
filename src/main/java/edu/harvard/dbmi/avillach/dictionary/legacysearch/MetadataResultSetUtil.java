package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.CategoricalMetadata;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.ContinuousMetadata;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Result;
import edu.harvard.dbmi.avillach.dictionary.util.JsonBlobParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;


@Component
public class MetadataResultSetUtil {

    private final static Logger log = LoggerFactory.getLogger(MetadataResultSetUtil.class);
    private final JsonBlobParser jsonBlobParser;

    @Autowired
    public MetadataResultSetUtil(JsonBlobParser jsonBlobParser) {
        this.jsonBlobParser = jsonBlobParser;
    }

    public Result mapContinuousMetadata(ResultSet rs) throws SQLException {
        String hashedVarId = hashVarId(rs.getString("conceptPath"));
        String description = getDescription(rs);
        String parentName = getParentName(rs);
        String parentDisplay = getParentDisplay(rs);

        String max = String.valueOf(jsonBlobParser.parseMax(rs.getString("values")));
        String min = String.valueOf(jsonBlobParser.parseMin(rs.getString("values")));

        ContinuousMetadata metadata = new ContinuousMetadata(
            rs.getString("stigmatized"), rs.getString("display"), description, min, rs.getString("conceptPath"), parentName,
            rs.getString("conceptPath"), rs.getString("name"), parentDisplay, description, // changed
            "{}", "", parentName, max, description, rs.getString("dataset"), hashedVarId, rs.getString("conceptType"), rs.getString("name"),
            rs.getString("dataset"), rs.getString("stigmatized"), rs.getString("display"), rs.getString("studyAcronym"),
            rs.getString("dsFullName"), parentName, parentDisplay, rs.getString("conceptPath"), min, max
        );
        return new Result(
            metadata, jsonBlobParser.parseValues(rs.getString("values")), rs.getString("dataset"), parentName, rs.getString("name"), false,
            true
        );
    }



    public Result mapCategoricalMetadata(ResultSet rs) throws SQLException {
        String hashedVarId = hashVarId(rs.getString("conceptPath"));
        String description = getDescription(rs);
        String parentName = getParentName(rs);
        String parentDisplay = getParentDisplay(rs);

        CategoricalMetadata metadata = new CategoricalMetadata(
            rs.getString("stigmatized"), rs.getString("display"), description, "", rs.getString("conceptPath"), parentName,
            rs.getString("conceptPath"), rs.getString("name"), parentDisplay, description, // changed
            "{}", "", parentName, "", description, rs.getString("dataset"), hashedVarId, rs.getString("conceptType"), rs.getString("name"),
            rs.getString("dataset"), rs.getString("stigmatized"), rs.getString("display"), rs.getString("studyAcronym"),
            rs.getString("dsFullName"), parentName, parentDisplay, rs.getString("conceptPath")
        );

        return new Result(
            metadata, jsonBlobParser.parseValues(rs.getString("values")), rs.getString("dataset"), parentName, rs.getString("name"), true,
            false
        );
    }

    private static String hashVarId(String hpdsPath) {
        String hashedVarId = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(hpdsPath.getBytes(StandardCharsets.UTF_8));
            hashedVarId = bytesToHex(encodedHash);
        } catch (NoSuchAlgorithmException e) {
            log.error(e.getMessage());
        }

        return hashedVarId;
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static String getParentDisplay(ResultSet rs) throws SQLException {
        return rs.getString("parentDisplay") == null || rs.getString("parentDisplay").isBlank() ? "" : rs.getString("parentDisplay");
    }

    private static String getParentName(ResultSet rs) throws SQLException {
        return rs.getString("parentName") == null || rs.getString("parentName").isBlank() ? "All Variables" : rs.getString("parentName");
    }

    private static String getDescription(ResultSet rs) throws SQLException {
        return rs.getString("description") == null || rs.getString("description").isBlank() ? "" : rs.getString("description");
    }
}
