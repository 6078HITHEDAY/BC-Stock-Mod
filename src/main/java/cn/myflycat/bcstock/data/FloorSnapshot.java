package cn.myflycat.bcstock.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/floors} 一整包。只覆盖还活着的公司；破产的不在表里。
 */
public final class FloorSnapshot {

    private final Map<String, FloorView> byName;
    private final Map<Integer, FloorView> byApiId;
    private final double lastRefreshed;
    private final String lastError;
    private final String lastExternalError;
    private final double pollInterval;

    public FloorSnapshot(Map<String, FloorView> byName, Map<Integer, FloorView> byApiId,
                         double lastRefreshed, String lastError, String lastExternalError,
                         double pollInterval) {
        this.byName = (byName == null) ? Map.of() : Map.copyOf(byName);
        this.byApiId = (byApiId == null) ? Map.of() : Map.copyOf(byApiId);
        this.lastRefreshed = lastRefreshed;
        this.lastError = lastError;
        this.lastExternalError = lastExternalError;
        this.pollInterval = pollInterval;
    }

    public Map<String, FloorView> byName() {
        return byName;
    }

    public Map<Integer, FloorView> byApiId() {
        return byApiId;
    }

    public FloorView ofName(String name) {
        return name == null ? null : byName.get(name);
    }

    public FloorView ofApiId(int apiId) {
        return byApiId.get(apiId);
    }

    public boolean hasError() {
        return (lastError != null && !lastError.isBlank())
                || (lastExternalError != null && !lastExternalError.isBlank());
    }

    public double lastRefreshed() {
        return lastRefreshed;
    }

    public double pollInterval() {
        return pollInterval;
    }

    /** 公司名 → distance_pct（只含 known 行）。给 AlertRules 用。 */
    public Map<String, Double> distancePctByName() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (FloorView f : byName.values()) {
            if (f.known()) {
                out.put(f.companyName(), f.distancePct());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public static FloorSnapshot parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error")) {
            return null;
        }
        String lastError = text(root, "last_error");
        String lastExternalError = text(root, "last_external_error");
        if ((lastError != null && !lastError.isBlank())
                || (lastExternalError != null && !lastExternalError.isBlank())) {
            // 有错就整包不可信
            return new FloorSnapshot(Map.of(), Map.of(),
                    doubleOr(root, "last_refreshed", Double.NaN),
                    lastError, lastExternalError,
                    doubleOr(root, "poll_interval", 300.0));
        }
        if (!root.has("floors") || !root.get("floors").isJsonArray()) {
            return null;
        }
        JsonArray arr = root.getAsJsonArray("floors");
        Map<String, FloorView> byName = new LinkedHashMap<>();
        Map<Integer, FloorView> byApiId = new LinkedHashMap<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            FloorView v = parseOne(el.getAsJsonObject());
            if (v == null || !v.known()) {
                continue;
            }
            byName.put(v.companyName(), v);
            if (v.companyId() >= 0) {
                byApiId.put(v.companyId(), v);
            }
        }
        return new FloorSnapshot(byName, byApiId,
                doubleOr(root, "last_refreshed", Double.NaN),
                null, null,
                doubleOr(root, "poll_interval", 300.0));
    }

    private static FloorView parseOne(JsonObject o) {
        String name = text(o, "company_name");
        if (name == null || name.isBlank()) {
            return null;
        }
        int id = intOr(o, "company_id", -1);
        double floor = doubleOr(o, "floor", Double.NaN);
        double current = doubleOr(o, "current", Double.NaN);
        double distancePct = doubleOr(o, "distance_pct", Double.NaN);
        double dangerZone = doubleOr(o, "danger_zone", Double.NaN);
        boolean inDanger = o.has("in_danger_zone") && !o.get("in_danger_zone").isJsonNull()
                && o.get("in_danger_zone").getAsBoolean();
        double crash = doubleOr(o, "crash_risk_per_tick", Double.NaN);
        String status = text(o, "status");
        if (status == null) {
            status = "";
        }
        return new FloorView(id, name, floor, current, distancePct, dangerZone,
                inDanger, crash, status);
    }

    private static String text(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        return o.get(key).getAsInt();
    }

    private static double doubleOr(JsonObject o, String key, double fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        return o.get(key).getAsDouble();
    }
}
