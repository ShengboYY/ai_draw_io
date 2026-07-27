package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.DiagramStyleDefaults;
import org.zipp.ai.domain.chartbook.model.valobj.ProfileState;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfilePO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Keeps JSON persistence deterministic so Context pins and audit rows share one digest. */
final class ChartbookProfilePersistenceCodec {

    private ChartbookProfilePersistenceCodec() {
    }

    static ChartbookProfile decode(ChartbookProfilePO po) {
        return new ChartbookProfile(
                po.getChartbookId(),
                po.getVersion(),
                po.getInstructions(),
                po.getGoal(),
                po.getSummary(),
                parseMap(po.getGlossaryJson()),
                new DiagramStyleDefaults(parseMap(po.getDefaultStyleJson())),
                parseList(po.getStableConstraintsJson()),
                ProfileState.valueOf(po.getProfileState()),
                po.getUpdatedAt());
    }

    static String glossaryJson(ChartbookProfile profile) {
        return JSON.toJSONString(new TreeMap<>(profile.glossary()));
    }

    static String defaultStyleJson(ChartbookProfile profile) {
        return JSON.toJSONString(new TreeMap<>(profile.defaultStyle().values()));
    }

    static String stableConstraintsJson(ChartbookProfile profile) {
        return JSON.toJSONString(profile.stableConstraints());
    }

    static String digest(ChartbookProfile profile) {
        return digestRaw(profile.chartbookId(), profile.version(), profile.instructions(), profile.goal(),
                profile.summary(), glossaryJson(profile), defaultStyleJson(profile),
                stableConstraintsJson(profile), profile.profileState().name());
    }

    static String digestRaw(String chartbookId, long version, String instructions, String goal,
                            String summary, String glossaryJson, String defaultStyleJson,
                            String stableConstraintsJson, String profileState) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String[] values = {chartbookId, Long.toString(version), instructions, goal, summary,
                    glossaryJson, defaultStyleJson, stableConstraintsJson, profileState};
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> values = JSON.parseObject(json, Map.class);
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        }
        return result;
    }

    static List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Object> values = JSON.parseArray(json);
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            result.add(value == null ? "" : String.valueOf(value));
        }
        return result;
    }
}
