package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned persistence codec. Invalid records are never silently replaced or skipped. */
final class MoneyCoachJson {
    private MoneyCoachJson() { }
    static String write(MoneyCoach.State state) {
        try {
            JSONObject root = new JSONObject().put("schemaVersion", MoneyCoach.SCHEMA_VERSION)
                    .put("practiceStart", state.practiceStart.toString()).put("goosePercent", state.goosePercent)
                    .put("dreamPercent", state.dreamPercent).put("dailyPercent", state.dailyPercent);
            JSONArray wishes = new JSONArray();
            for (MoneyCoach.Wish w : state.wishes) wishes.put(new JSONObject().put("slot", w.slot)
                    .put("title", w.title).put("reason", w.reason).put("priority", w.priority)
                    .put("targetMinor", w.targetMinor).put("savedMinor", w.savedMinor)
                    .put("targetDate", w.targetDate == null ? JSONObject.NULL : w.targetDate.toString()).put("imageUri", w.imageUri));
            root.put("wishes", wishes);
            JSONArray allocations = new JSONArray();
            for (MoneyCoach.AllocationRecord r : state.allocations) allocations.put(new JSONObject().put("id", r.id)
                    .put("amountMinor", r.amountMinor).put("goosePercent", r.goosePercent).put("dreamPercent", r.dreamPercent)
                    .put("dailyPercent", r.dailyPercent).put("createdAt", r.createdAt).put("executedAt", r.executedAt).put("note", r.note));
            root.put("allocations", allocations);
            JSONArray successes = new JSONArray();
            for (MoneyCoach.SuccessEntry e : state.successes) successes.put(new JSONObject().put("date", e.date.toString()).put("items", new JSONArray(e.items)));
            root.put("successes", successes);
            JSONArray actions = new JSONArray();
            for (MoneyCoach.Action a : state.actions) actions.put(new JSONObject().put("id", a.id).put("title", a.title)
                    .put("createdAt", a.createdAt).put("completedAt", a.completedAt).put("obstacle", a.obstacle));
            root.put("actions", actions);
            JSONArray reviews = new JSONArray();
            for (MoneyCoach.WeeklyReview r : state.reviews) reviews.put(new JSONObject().put("weekStart", r.weekStart.toString())
                    .put("progress", r.progress).put("obstacle", r.obstacle).put("nextStep", r.nextStep));
            root.put("reviews", reviews);
            JSONArray practices = new JSONArray();
            for (MoneyCoach.PracticeEntry e : state.practices) practices.put(new JSONObject().put("date", e.date.toString())
                    .put("themeIndex", e.themeIndex).put("understanding", e.understanding).put("experience", e.experience));
            root.put("practices", practices);
            String result = root.toString();
            if (result.getBytes(StandardCharsets.UTF_8).length > MoneyCoach.MAX_STATE_BYTES) {
                throw new IllegalArgumentException("练习记录已达到当前版本保存上限，原有记录保持不变");
            }
            return result;
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("练习记录无法保存，原有记录保持不变", failure); }
    }

    static MoneyCoach.State read(String source) {
        try {
            JSONObject root = JsonInput.object(source, MoneyCoach.MAX_STATE_BYTES);
            keys(root, "schemaVersion", "practiceStart", "goosePercent", "dreamPercent", "dailyPercent", "wishes", "allocations", "successes", "actions", "reviews", "practices");
            if (integer(root, "schemaVersion") != MoneyCoach.SCHEMA_VERSION) throw invalid();
            List<MoneyCoach.Wish> wishes = new ArrayList<>();
            JSONArray ws = array(root, "wishes");
            if (ws.length() != 10) throw invalid();
            for (int i = 0; i < ws.length(); i++) {
                JSONObject w = object(ws, i);
                keys(w, "slot", "title", "reason", "priority", "targetMinor", "savedMinor", "targetDate", "imageUri");
                wishes.add(new MoneyCoach.Wish(smallInteger(w, "slot"), string(w, "title"), string(w, "reason"), bool(w, "priority"),
                        integer(w, "targetMinor"), integer(w, "savedMinor"), w.get("targetDate") == JSONObject.NULL ? null : date(w, "targetDate"), string(w, "imageUri")));
            }
            List<MoneyCoach.AllocationRecord> allocations = new ArrayList<>();
            JSONArray ars = array(root, "allocations");
            for (int i = 0; i < ars.length(); i++) {
                JSONObject r = object(ars, i);
                keys(r, "id", "amountMinor", "goosePercent", "dreamPercent", "dailyPercent", "createdAt", "executedAt", "note");
                allocations.add(new MoneyCoach.AllocationRecord(string(r, "id"), integer(r, "amountMinor"), smallInteger(r, "goosePercent"),
                        smallInteger(r, "dreamPercent"), smallInteger(r, "dailyPercent"), integer(r, "createdAt"), integer(r, "executedAt"), string(r, "note")));
            }
            List<MoneyCoach.SuccessEntry> successes = new ArrayList<>();
            JSONArray ses = array(root, "successes");
            for (int i = 0; i < ses.length(); i++) {
                JSONObject e = object(ses, i);
                keys(e, "date", "items");
                JSONArray items = array(e, "items");
                if (items.length() > 100) throw invalid();
                List<String> texts = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) {
                    Object item = items.get(j);
                    if (!(item instanceof String)) throw invalid();
                    texts.add((String) item);
                }
                successes.add(new MoneyCoach.SuccessEntry(date(e, "date"), texts));
            }
            List<MoneyCoach.Action> actions = new ArrayList<>();
            JSONArray as = array(root, "actions");
            for (int i = 0; i < as.length(); i++) {
                JSONObject a = object(as, i);
                keys(a, "id", "title", "createdAt", "completedAt", "obstacle");
                actions.add(new MoneyCoach.Action(string(a, "id"), string(a, "title"), integer(a, "createdAt"), integer(a, "completedAt"), string(a, "obstacle")));
            }
            List<MoneyCoach.WeeklyReview> reviews = new ArrayList<>();
            JSONArray rs = array(root, "reviews");
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = object(rs, i);
                keys(r, "weekStart", "progress", "obstacle", "nextStep");
                reviews.add(new MoneyCoach.WeeklyReview(date(r, "weekStart"), string(r, "progress"), string(r, "obstacle"), string(r, "nextStep")));
            }
            List<MoneyCoach.PracticeEntry> practices = new ArrayList<>();
            JSONArray ps = array(root, "practices");
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = object(ps, i);
                keys(p, "date", "themeIndex", "understanding", "experience");
                practices.add(new MoneyCoach.PracticeEntry(date(p, "date"), smallInteger(p, "themeIndex"), string(p, "understanding"), string(p, "experience")));
            }
            return new MoneyCoach.State(date(root, "practiceStart"), smallInteger(root, "goosePercent"), smallInteger(root, "dreamPercent"),
                    smallInteger(root, "dailyPercent"), wishes, allocations, successes, actions, reviews, practices);
        } catch (Exception failure) { throw new IllegalArgumentException("练习数据无法读取，已保留原始数据，请勿覆盖或清空", failure); }
    }
    private static void keys(JSONObject json, String... expected) {
        Set<String> actual = new HashSet<>();
        json.keys().forEachRemaining(actual::add);
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) throw invalid();
    }
    private static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }
    private static long integer(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        return ((Number) value).longValue();
    }
    private static int smallInteger(JSONObject json, String key) throws Exception {
        long value = integer(json, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }
    private static boolean bool(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Boolean)) throw invalid();
        return (Boolean) value;
    }
    private static LocalDate date(JSONObject json, String key) throws Exception {
        String value = string(json, key);
        LocalDate parsed = LocalDate.parse(value);
        if (!parsed.toString().equals(value)) throw invalid();
        return parsed;
    }
    private static JSONArray array(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() > MoneyCoach.MAX_RECORDS) throw invalid();
        return (JSONArray) value;
    }
    private static JSONObject object(JSONArray array, int index) throws Exception {
        Object value = array.get(index);
        if (!(value instanceof JSONObject)) throw invalid();
        return (JSONObject) value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid coach state"); }
}
