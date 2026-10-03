package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MoneyCoachJsonTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final long NOW = Instant.parse("2026-10-03T10:00:00Z").toEpochMilli();

    @Test public void allSixPracticeTypesSurviveRoundtripIncludingDraftsAndHistoricalThemes() {
        MoneyCoach.State source = fullState();
        MoneyCoach.State loaded = MoneyCoach.State.fromJson(source.toJson());
        assertEquals(MONDAY, loaded.practiceStart);
        assertEquals(10, loaded.wishes.size());
        assertNull(loaded.wishes.get(1).targetDate);
        assertEquals("content://local/1", loaded.wishes.get(0).imageUri);
        assertEquals("因为想多看看世界\n与家人一起", loaded.wishes.get(0).reason);
        assertEquals(200, loaded.wishes.get(0).savedMinor);
        assertEquals(1, loaded.allocations.size());
        assertFalse(loaded.allocations.get(0).isExecuted());
        assertEquals(101, loaded.allocations.get(0).allocation.totalMinor());
        assertEquals(2, loaded.successes.get(0).items.size());
        assertEquals("还需要时间", loaded.actions.get(0).obstacle);
        assertEquals(NOW + MoneyCoach.ACTION_WINDOW_MILLIS, loaded.actions.get(0).deadlineAt);
        assertEquals("下周一步", loaded.reviews.get(0).nextStep);
        assertEquals(2, loaded.practices.size());
        assertEquals(loaded.practices.get(0).themeIndex, loaded.practices.get(1).themeIndex);
        assertNotEquals(loaded.practices.get(0).experience, loaded.practices.get(1).experience);
    }

    @Test public void blankNewStateIsExplicitlyVersionedAndHasNoFictionalRecords() throws Exception {
        MoneyCoach.State state = MoneyCoach.State.fromJson(MoneyCoach.State.empty(MONDAY).toJson());
        assertEquals(10, state.wishes.size());
        assertTrue(state.allocations.isEmpty() && state.successes.isEmpty() && state.actions.isEmpty()
                && state.reviews.isEmpty() && state.practices.isEmpty());
        assertEquals(1, new JSONObject(state.toJson()).getInt("schemaVersion"));
    }

    @Test public void numericStringsFloatingMoneyAndWrongBooleanAreRejected() throws Exception {
        for (Object value : new Object[]{"101", 101.5, JSONObject.NULL, true}) {
            JSONObject json = new JSONObject(fullState().toJson());
            json.getJSONArray("allocations").getJSONObject(0).put("amountMinor", value);
            assertInvalid(json.toString());
        }
        JSONObject json = new JSONObject(fullState().toJson());
        json.getJSONArray("wishes").getJSONObject(0).put("priority", "true");
        assertInvalid(json.toString());
        assertInvalid(fullState().toJson().replace("\"amountMinor\":101", "\"amountMinor\":101.0"));
    }

    @Test public void unsupportedSchemaMissingFieldsAndUnexpectedFieldsAreNotDiscarded() throws Exception {
        JSONObject json = new JSONObject(fullState().toJson());
        json.put("schemaVersion", 2);
        assertInvalid(json.toString());
        json = new JSONObject(fullState().toJson());
        json.remove("successes");
        assertInvalid(json.toString());
        json = new JSONObject(fullState().toJson());
        json.getJSONArray("actions").getJSONObject(0).put("newField", 1);
        assertInvalid(json.toString());
    }

    @Test public void malformedJsonDuplicateKeysAndTrailingDataCannotBecomeAnEmptyState() {
        String valid = fullState().toJson();
        for (String input : Arrays.asList(null, "", "{}", "{broken}", valid + "{}", "[" + valid + "]",
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))) {
            assertInvalid(input);
        }
    }

    @Test public void duplicateRecordIdentitiesAndMissingWishSlotsAreRejected() throws Exception {
        for (String field : Arrays.asList("wishes", "allocations", "successes", "actions", "reviews", "practices")) {
            JSONObject json = new JSONObject(fullState().toJson());
            JSONArray values = json.getJSONArray(field);
            if (field.equals("wishes")) values.put(1, values.get(0));
            else values.put(values.get(0));
            assertInvalid(json.toString());
        }
        JSONObject json = new JSONObject(fullState().toJson());
        json.put("wishes", new JSONArray());
        assertInvalid(json.toString());
    }

    @Test public void invalidRealDatesAndWrongNestedTypesDoNotGetCoerced() throws Exception {
        JSONObject json = new JSONObject(fullState().toJson());
        json.getJSONArray("successes").getJSONObject(0).put("date", "2026-02-29");
        assertInvalid(json.toString());
        json = new JSONObject(fullState().toJson());
        json.getJSONArray("wishes").getJSONObject(1).put("targetDate", 20261003);
        assertInvalid(json.toString());
        json = new JSONObject(fullState().toJson());
        json.put("reviews", "[]");
        assertInvalid(json.toString());
        json = new JSONObject(fullState().toJson());
        json.getJSONArray("successes").getJSONObject(0).getJSONArray("items").put(3);
        assertInvalid(json.toString());
    }

    @Test public void stateLargerThanWechatPayloadLimitPreservesLongTermHistory() {
        List<MoneyCoach.SuccessEntry> entries = new ArrayList<>();
        for (int i = 0; i < 150; i++) entries.add(new MoneyCoach.SuccessEntry(MONDAY.plusDays(i),
                Arrays.asList("每天一点学习。".repeat(400))));
        MoneyCoach.State state = new MoneyCoach.State(MONDAY, 50, 40, 10, Collections.emptyList(),
                Collections.emptyList(), entries, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        String encoded = state.toJson();
        assertTrue(encoded.length() > WechatBillParser.MAX_INPUT_BYTES);
        assertEquals(150, MoneyCoach.State.fromJson(encoded).successes.size());
    }

    @Test public void stateSizeLimitFailsBeforePersistenceRatherThanTruncatingOldRecords() {
        List<MoneyCoach.PracticeEntry> entries = new ArrayList<>();
        String text = "练".repeat(8000);
        for (int i = 0; i < 200; i++) entries.add(new MoneyCoach.PracticeEntry(MONDAY.plusDays(i), i % 7, text, text));
        MoneyCoach.State state = new MoneyCoach.State(MONDAY, 50, 40, 10, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), entries);
        assertThrows(IllegalArgumentException.class, state::toJson);
        assertEquals(200, state.practices.size());
    }

    private static void assertInvalid(String json) {
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.State.fromJson(json));
    }

    private static MoneyCoach.State fullState() {
        return new MoneyCoach.State(MONDAY, 50, 40, 10,
                Arrays.asList(new MoneyCoach.Wish(0, "旅行", "因为想多看看世界\n与家人一起", true, 100, 200, MONDAY.plusDays(90), "content://local/1")),
                Arrays.asList(new MoneyCoach.AllocationRecord("allocation-1", 101, 50, 40, 10, NOW, 0, "虚构计划")),
                Arrays.asList(new MoneyCoach.SuccessEntry(MONDAY, Arrays.asList("迈出一步", ""))),
                Arrays.asList(new MoneyCoach.Action("action-1", "找一张目标图片", NOW, 0, "还需要时间")),
                Arrays.asList(new MoneyCoach.WeeklyReview(MONDAY, "本周一步", "遇到困难", "下周一步")),
                Arrays.asList(new MoneyCoach.PracticeEntry(MONDAY, 0, "听清别人说什么", "一次认真倾听"),
                        new MoneyCoach.PracticeEntry(MONDAY.plusDays(7), 0, "继续练习", "第二周经历")));
    }
}
