package dev.tallybook.app;

import android.content.Context;
import android.content.SharedPreferences;
import dev.tallybook.core.MoneyCoach;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Private local exercises, isolated by source and entirely independent of actual ledger balances. */
public final class MoneyCoachStore {
    // A shared lock also covers separate store instances used by activities in this app process.
    private static final Object LOCK = new Object();
    private static final String STATE_KEY = "state";
    private final SharedPreferences prefs;

    public MoneyCoachStore(Context context, String source) {
        if (!LedgerStore.WECHAT.equals(source) && !LedgerStore.DEMO.equals(source)) {
            throw new IllegalArgumentException("未知练习账本来源");
        }
        prefs = context.getApplicationContext().getSharedPreferences("money_coach_" + source, Context.MODE_PRIVATE);
    }

    /** First use persists the start week so the seven-day cycle does not restart on every open. */
    public MoneyCoach.State load() {
        synchronized (LOCK) { return loadLocked(); }
    }

    public void saveWish(MoneyCoach.Wish wish) {
        require(wish);
        mutate(draft -> draft.wishes.set(wish.slot, wish));
    }

    public void saveWishes(List<MoneyCoach.Wish> wishes) {
        if (wishes == null) throw new IllegalArgumentException("缺少愿望列表");
        List<MoneyCoach.Wish> copy = new ArrayList<>(wishes);
        mutate(draft -> draft.wishes = copy);
    }

    public void saveRatios(int goosePercent, int dreamPercent, int dailyPercent) {
        // Validate before a first-use initialization as well as during final snapshot construction.
        MoneyCoach.allocate(0, goosePercent, dreamPercent, dailyPercent);
        mutate(draft -> {
            draft.goosePercent = goosePercent;
            draft.dreamPercent = dreamPercent;
            draft.dailyPercent = dailyPercent;
        });
    }

    public void saveAllocation(MoneyCoach.AllocationRecord record) {
        require(record);
        mutate(draft -> {
            upsert(draft.allocations, record, item -> item.id);
            draft.allocations.sort(Comparator.comparingLong((MoneyCoach.AllocationRecord item) -> item.createdAt)
                    .reversed().thenComparing(item -> item.id));
        });
    }

    public void deleteAllocation(String id) {
        requireKey(id);
        mutate(draft -> draft.allocations.removeIf(item -> item.id.equals(id)));
    }

    public void saveSuccess(MoneyCoach.SuccessEntry entry) {
        require(entry);
        mutate(draft -> {
            upsert(draft.successes, entry, item -> item.date);
            draft.successes.sort(Comparator.comparing((MoneyCoach.SuccessEntry item) -> item.date).reversed());
        });
    }

    public void deleteSuccess(LocalDate date) {
        require(date);
        mutate(draft -> draft.successes.removeIf(item -> item.date.equals(date)));
    }

    public void saveAction(MoneyCoach.Action action) {
        require(action);
        mutate(draft -> {
            upsert(draft.actions, action, item -> item.id);
            draft.actions.sort(Comparator.comparingLong((MoneyCoach.Action item) -> item.createdAt)
                    .reversed().thenComparing(item -> item.id));
        });
    }

    public void completeAction(String id, long when) {
        requireKey(id);
        mutate(draft -> {
            for (int i = 0; i < draft.actions.size(); i++) {
                MoneyCoach.Action action = draft.actions.get(i);
                if (action.id.equals(id)) {
                    // Repeated taps do not change the original completion evidence.
                    if (action.completedAt == 0) draft.actions.set(i, action.complete(when));
                    return;
                }
            }
            throw new IllegalArgumentException("这条小行动已不存在，请返回列表查看");
        });
    }

    public void deleteAction(String id) {
        requireKey(id);
        mutate(draft -> draft.actions.removeIf(item -> item.id.equals(id)));
    }

    public void saveReview(MoneyCoach.WeeklyReview review) {
        require(review);
        mutate(draft -> {
            upsert(draft.reviews, review, item -> item.weekStart);
            draft.reviews.sort(Comparator.comparing((MoneyCoach.WeeklyReview item) -> item.weekStart).reversed());
        });
    }

    public void deleteReview(LocalDate weekStart) {
        require(weekStart);
        mutate(draft -> draft.reviews.removeIf(item -> item.weekStart.equals(weekStart)));
    }

    public void savePractice(MoneyCoach.PracticeEntry entry) {
        require(entry);
        mutate(draft -> {
            upsert(draft.practices, entry, item -> item.date);
            draft.practices.sort(Comparator.comparing((MoneyCoach.PracticeEntry item) -> item.date).reversed());
        });
    }

    public void deletePractice(LocalDate date) {
        require(date);
        mutate(draft -> draft.practices.removeIf(item -> item.date.equals(date)));
    }

    private MoneyCoach.State loadLocked() {
        if (!prefs.contains(STATE_KEY)) {
            MoneyCoach.State initial = MoneyCoach.State.empty(MoneyCoach.weekStart(LocalDate.now()));
            writeLocked(initial);
            return initial;
        }
        try {
            // getString deliberately throws for a preference of the wrong type.
            return MoneyCoach.State.fromJson(prefs.getString(STATE_KEY, null));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("理财练习暂时无法读取，原始数据已保留；请勿覆盖或清空。", failure);
        }
    }

    private void mutate(Consumer<Draft> update) {
        synchronized (LOCK) {
            // Reading must succeed before any write. Corruption cannot be hidden by saving a new item.
            Draft draft = new Draft(loadLocked());
            update.accept(draft);
            writeLocked(draft.freeze());
        }
    }

    private void writeLocked(MoneyCoach.State state) {
        String encoded = state.toJson();
        if (!prefs.edit().putString(STATE_KEY, encoded).commit()) {
            throw new IllegalStateException("未能确认练习记录已写入本机，请重新打开后检查。");
        }
    }

    private static <T, K> void upsert(List<T> items, T value, Function<T, K> key) {
        K identity = key.apply(value);
        for (int i = 0; i < items.size(); i++) {
            if (key.apply(items.get(i)).equals(identity)) {
                items.set(i, value);
                return;
            }
        }
        items.add(value);
    }

    private static void require(Object value) {
        if (value == null) throw new IllegalArgumentException("缺少要保存的练习内容");
    }

    private static void requireKey(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("练习记录编号无效");
    }

    private static final class Draft {
        final LocalDate practiceStart;
        int goosePercent, dreamPercent, dailyPercent;
        List<MoneyCoach.Wish> wishes;
        final List<MoneyCoach.AllocationRecord> allocations;
        final List<MoneyCoach.SuccessEntry> successes;
        final List<MoneyCoach.Action> actions;
        final List<MoneyCoach.WeeklyReview> reviews;
        final List<MoneyCoach.PracticeEntry> practices;
        Draft(MoneyCoach.State state) {
            practiceStart = state.practiceStart;
            goosePercent = state.goosePercent; dreamPercent = state.dreamPercent; dailyPercent = state.dailyPercent;
            wishes = new ArrayList<>(state.wishes); allocations = new ArrayList<>(state.allocations);
            successes = new ArrayList<>(state.successes); actions = new ArrayList<>(state.actions);
            reviews = new ArrayList<>(state.reviews); practices = new ArrayList<>(state.practices);
        }
        MoneyCoach.State freeze() {
            return new MoneyCoach.State(practiceStart, goosePercent, dreamPercent, dailyPercent,
                    wishes, allocations, successes, actions, reviews, practices);
        }
    }
}
