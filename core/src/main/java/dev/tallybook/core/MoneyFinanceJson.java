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

/** Strict versioned codec; balances are recomputed, never trusted from storage. */
final class MoneyFinanceJson {
    private MoneyFinanceJson() { }
    static String write(MoneyFinance.State state) {
        try {
            JSONArray entries = new JSONArray(), projects = new JSONArray(), flows = new JSONArray(), items = new JSONArray();
            for (MoneyFinance.PotEntry e : state.entries) entries.put(new JSONObject().put("id", e.id).put("kind", e.kind)
                    .put("transactionId", e.transactionId).put("fromPot", e.fromPot).put("toPot", e.toPot)
                    .put("amountMinor", e.amountMinor).put("gooseMinor", e.gooseMinor).put("dreamMinor", e.dreamMinor)
                    .put("dailyMinor", e.dailyMinor).put("date", e.date.toString()).put("note", e.note));
            for (MoneyFinance.Project p : state.projects) projects.put(new JSONObject().put("id", p.id).put("title", p.title)
                    .put("need", p.need).put("offer", p.offer).put("resources", p.resources).put("expectedMinor", p.expectedMinor)
                    .put("dueDate", dateJson(p.dueDate)).put("note", p.note).put("archived", p.archived));
            for (MoneyFinance.ProjectFlow f : state.flows) flows.put(new JSONObject().put("id", f.id).put("projectId", f.projectId)
                    .put("kind", f.kind).put("amountMinor", f.amountMinor).put("date", f.date.toString()).put("note", f.note));
            for (MoneyFinance.Item i : state.items) items.put(new JSONObject().put("id", i.id).put("kind", i.kind).put("title", i.title)
                    .put("amountMinor", i.amountMinor).put("dueDate", dateJson(i.dueDate)).put("minPaymentMinor", i.minPaymentMinor).put("note", i.note));
            String result = new JSONObject().put("schemaVersion", MoneyFinance.SCHEMA_VERSION).put("entries", entries)
                    .put("projects", projects).put("flows", flows).put("items", items).toString();
            if (result.getBytes(StandardCharsets.UTF_8).length > MoneyFinance.MAX_STATE_BYTES) throw invalid();
            return result;
        } catch (Exception failure) { throw new IllegalArgumentException("资金练习未能保存，原有记录保持不变", failure); }
    }
    static MoneyFinance.State read(String text) {
        try {
            JSONObject root = JsonInput.object(text, MoneyFinance.MAX_STATE_BYTES);
            keys(root, "schemaVersion", "entries", "projects", "flows", "items");
            if (integer(root, "schemaVersion") != MoneyFinance.SCHEMA_VERSION) throw invalid();
            List<MoneyFinance.PotEntry> entries = new ArrayList<>();
            JSONArray es = array(root, "entries");
            for (int n = 0; n < es.length(); n++) {
                JSONObject e = object(es, n);
                keys(e, "id", "kind", "transactionId", "fromPot", "toPot", "amountMinor", "gooseMinor", "dreamMinor", "dailyMinor", "date", "note");
                entries.add(new MoneyFinance.PotEntry(string(e,"id"), string(e,"kind"), string(e,"transactionId"), string(e,"fromPot"), string(e,"toPot"),
                        integer(e,"amountMinor"), integer(e,"gooseMinor"), integer(e,"dreamMinor"), integer(e,"dailyMinor"), date(e,"date", false), string(e,"note")));
            }
            List<MoneyFinance.Project> projects = new ArrayList<>();
            JSONArray ps = array(root, "projects");
            for (int n = 0; n < ps.length(); n++) {
                JSONObject p = object(ps, n);
                keys(p,"id","title","need","offer","resources","expectedMinor","dueDate","note","archived");
                Object archived = p.get("archived"); if (!(archived instanceof Boolean)) throw invalid();
                projects.add(new MoneyFinance.Project(string(p,"id"),string(p,"title"),string(p,"need"),string(p,"offer"),string(p,"resources"),
                        integer(p,"expectedMinor"),date(p,"dueDate",true),string(p,"note"),(Boolean) archived));
            }
            List<MoneyFinance.ProjectFlow> flows = new ArrayList<>();
            JSONArray fs = array(root,"flows");
            for (int n = 0; n < fs.length(); n++) {
                JSONObject f = object(fs,n); keys(f,"id","projectId","kind","amountMinor","date","note");
                flows.add(new MoneyFinance.ProjectFlow(string(f,"id"),string(f,"projectId"),string(f,"kind"),integer(f,"amountMinor"),date(f,"date",false),string(f,"note")));
            }
            List<MoneyFinance.Item> items = new ArrayList<>();
            JSONArray is = array(root,"items");
            for (int n = 0; n < is.length(); n++) {
                JSONObject i = object(is,n); keys(i,"id","kind","title","amountMinor","dueDate","minPaymentMinor","note");
                items.add(new MoneyFinance.Item(string(i,"id"),string(i,"kind"),string(i,"title"),integer(i,"amountMinor"),date(i,"dueDate",true),integer(i,"minPaymentMinor"),string(i,"note")));
            }
            return new MoneyFinance.State(entries,projects,flows,items);
        } catch (Exception failure) { throw new IllegalArgumentException("资金练习数据无法读取，原始数据已保留，请勿覆盖或清空", failure); }
    }
    private static Object dateJson(LocalDate date) { return date == null ? JSONObject.NULL : date.toString(); }
    private static void keys(JSONObject value, String... expected) {
        Set<String> found = new HashSet<>(); value.keys().forEachRemaining(found::add);
        if (!found.equals(new HashSet<>(Arrays.asList(expected)))) throw invalid();
    }
    private static String string(JSONObject value,String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof String)) throw invalid(); return (String) raw;
    }
    private static long integer(JSONObject value,String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof Integer) && !(raw instanceof Long)) throw invalid(); return ((Number) raw).longValue();
    }
    private static LocalDate date(JSONObject value,String key,boolean nullable) throws Exception {
        if (nullable && value.get(key) == JSONObject.NULL) return null;
        String text = string(value,key); LocalDate date = LocalDate.parse(text);
        if (!date.toString().equals(text)) throw invalid(); return date;
    }
    private static JSONArray array(JSONObject value,String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof JSONArray) || ((JSONArray) raw).length() > MoneyFinance.MAX_RECORDS) throw invalid(); return (JSONArray) raw;
    }
    private static JSONObject object(JSONArray array,int index) throws Exception {
        Object raw = array.get(index); if (!(raw instanceof JSONObject)) throw invalid(); return (JSONObject) raw;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("资金记录格式无效"); }
}
