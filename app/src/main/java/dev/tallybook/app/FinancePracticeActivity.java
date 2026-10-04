package dev.tallybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import dev.tallybook.core.MoneyCoach;
import dev.tallybook.core.MoneyFinance;
import dev.tallybook.core.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-directed money practice. Every amount is local; no payment is initiated. */
public final class FinancePracticeActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 242);
    private static final int INK = Color.rgb(25, 47, 38);
    private static final int MUTED = Color.rgb(108, 120, 111);
    private static final int GREEN = Color.rgb(27, 69, 53);
    private static final int PALE = Color.rgb(229, 237, 225);
    private static final String[] SECTIONS = {"钱罐", "赚钱尝试", "资产债务"};
    private static final String[] POT_ACTIONS = {"分配已确认收入", "录入期初已有资金", "调整钱罐用途", "核销已记支出", "钱罐明细"};
    // Shared queue means an accepted write finishes before a recreated Activity loads.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private Bundle drafts = new Bundle();
    private String source;
    private int section;
    private int potAction;
    private String operationId = id();
    private String projectId = id();
    private String flowId = id();
    private String itemId = id();
    private int historyLimit = 20;
    private int restoreScroll;
    private boolean busy;
    private LinearLayout rootView;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView ioStatus;
    private MoneyFinance.State state;
    private MoneyCoach.State coach;
    private List<Transaction> incomes = new ArrayList<>();
    private List<Transaction> expenses = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        source = saved == null ? getIntent().getStringExtra("source") : saved.getString("source");
        if (!LedgerStore.WECHAT.equals(source) && !LedgerStore.DEMO.equals(source)) {
            Toast.makeText(this, "请从账本内进入资金实践", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        section = Math.max(0, Math.min(2, saved == null ? getIntent().getIntExtra("section", 0) : saved.getInt("section")));
        if (saved != null) {
            potAction = Math.max(0, Math.min(POT_ACTIONS.length - 1, saved.getInt("pot_action")));
            operationId = saved.getString("operation_id", operationId);
            projectId = saved.getString("project_id", projectId);
            flowId = saved.getString("flow_id", flowId);
            itemId = saved.getString("item_id", itemId);
            historyLimit = Math.max(20, Math.min(20000, saved.getInt("history_limit", 20)));
            restoreScroll = saved.getInt("scroll_y");
            Bundle previous = saved.getBundle("drafts");
            if (previous != null) drafts = previous;
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        reload();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        remember();
        out.putString("source", source);
        out.putInt("section", section);
        out.putInt("pot_action", potAction);
        out.putString("operation_id", operationId);
        out.putString("project_id", projectId);
        out.putString("flow_id", flowId);
        out.putString("item_id", itemId);
        out.putInt("history_limit", historyLimit);
        out.putInt("scroll_y", scroll == null ? 0 : scroll.getScrollY());
        out.putBundle("drafts", drafts);
        super.onSaveInstanceState(out);
    }

    private static final class Snapshot {
        MoneyFinance.State finance;
        MoneyCoach.State coach;
        List<Transaction> incomes;
        List<Transaction> expenses;
    }

    private Snapshot loadSnapshot(MoneyFinanceStore store) {
        Snapshot result = new Snapshot();
        result.finance = store.load();
        try { result.coach = new MoneyCoachStore(getApplicationContext(), source).load(); }
        catch (RuntimeException ignored) { /* A damaged diary must not hide the independent money journal. */ }
        result.incomes = store.listEligibleIncome();
        result.expenses = store.listEligibleExpenses();
        return result;
    }

    private void reload() {
        shell();
        heading("正在读取资金实践", "已有记录会在读取完成后显示。");
        setBusy(true, "正在读取…");
        IO.execute(() -> {
            Snapshot loaded = null;
            try (MoneyFinanceStore store = new MoneyFinanceStore(getApplicationContext(), source)) {
                loaded = loadSnapshot(store);
            } catch (RuntimeException ignored) { /* Do not print private records. */ }
            final Snapshot result = loaded;
            main.post(() -> {
                if (!alive()) return;
                busy = false;
                if (result != null) { accept(result); render(); }
                else {
                    shell();
                    heading("暂时无法读取资金实践", "现有记录没有被清空。请重试，此时不能保存新内容。");
                    action("重试读取", this::reload, true);
                }
            });
        });
    }

    private void accept(Snapshot loaded) {
        state = loaded.finance;
        coach = loaded.coach;
        incomes = loaded.incomes;
        expenses = loaded.expenses;
    }

    private interface Change { void run(MoneyFinanceStore store); }

    private void change(Change work, String success, Runnable after) {
        if (busy) return;
        remember();
        setBusy(true, "正在保存…");
        IO.execute(() -> {
            Snapshot loaded = null;
            String failure = null;
            boolean written = false;
            try (MoneyFinanceStore store = new MoneyFinanceStore(getApplicationContext(), source)) {
                work.run(store);
                written = true;
                loaded = loadSnapshot(store);
            } catch (IllegalArgumentException | IllegalStateException error) {
                failure = safeMessage(error);
            } catch (RuntimeException error) {
                failure = "暂时无法保存，请稍后重试。";
            }
            final Snapshot result = loaded;
            final String message = failure;
            final boolean committed = written;
            main.post(() -> {
                if (!alive()) return;
                setBusy(false, "");
                if (committed) {
                    after.run();
                    if (result != null) { accept(result); render(); toast(success); }
                    else { toast("已保存，但页面刷新失败；正在重新读取。"); reload(); }
                } else error(message);
            });
        });
    }

    private void shell() {
        inputs.clear();
        rootView = column();
        rootView.setBackgroundColor(BG);
        rootView.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(rootView);
        rootView.requestApplyInsets();
        LinearLayout top = column();
        top.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView back = text("‹ 返回钱钱计划", 14, GREEN, true);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinHeight(dp(44));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(text("资金实践" + (LedgerStore.DEMO.equals(source) ? " · 演示" : " · 真实账本"), 25, INK, true));
        ioStatus = text("", 12, MUTED, false);
        ioStatus.setVisibility(View.GONE);
        top.addView(ioStatus);
        if (state != null) {
            Spinner select = spinner(SECTIONS, section);
            select.setContentDescription("选择资金实践页面");
            top.addView(select);
            select.setOnItemSelectedListener(listener(position -> {
                if (section == position) return;
                remember(); section = position; restoreScroll = 0; render();
            }));
        }
        rootView.addView(top);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSaveEnabled(false);
        body = column();
        body.setPadding(dp(20), dp(8), dp(20), dp(28));
        scroll.addView(body);
        rootView.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void render() {
        shell();
        if (section == 0) pots();
        else if (section == 1) projects();
        else items();
        int y = restoreScroll;
        restoreScroll = 0;
        if (y > 0) scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void pots() {
        heading("给手里的钱一个用途", "先有已到账的钱，再安排用途。钱罐是本地分配记录，不会替你转账。梦想余额与愿望页手填进度分别保留。");
        if (coach == null) note("愿望资料暂时无法读取，原数据仍保留。这里暂按梦想编号显示，分配比例请自行确认。");
        long dreams = 0;
        for (int slot = 0; slot < 10; slot++) dreams = Math.addExact(dreams, state.balance("dream_" + slot));
        card("当前钱罐余额", "长期积累  " + money(state.balance("goose"))
                + "\n梦想合计  " + money(dreams) + "\n日常使用  " + money(state.balance("daily")));
        for (int slot = 0; slot < 10; slot++) {
            if (state.balance("dream_" + slot) != 0) note(dreamName(slot) + "  " + money(state.balance("dream_" + slot)));
        }
        Spinner choose = spinner(POT_ACTIONS, potAction);
        choose.setContentDescription("选择钱罐操作");
        body.addView(choose);
        choose.setOnItemSelectedListener(listener(position -> {
            if (position == potAction) return;
            remember(); potAction = position; operationId = id(); restoreScroll = 0; render();
        }));
        if (potAction == 0) allocate();
        else if (potAction == 1) opening();
        else if (potAction == 2) transfer();
        else if (potAction == 3) spend();
        else potHistory();
    }

    private String potPrefix() { return "pot" + potAction + "_"; }

    private void allocate() {
        String p = potPrefix();
        heading("分配已确认收入", "选择一笔未分配的已确认收入，一次分完。待核对的收入不会出现在这里。");
        if (incomes.isEmpty()) {
            note("暂无可分配收入。先在账本记录已收到的钱，或在赚钱尝试里记录实际收入。已有存款请用“录入期初已有资金”。");
            return;
        }
        transactionChoice(p + "transaction", "选择要分配的收入", incomes);
        field(p + "goose", "长期积累比例（%）", String.valueOf(coach == null ? 50 : coach.goosePercent), true);
        field(p + "dream", "梦想比例（%）", String.valueOf(coach == null ? 40 : coach.dreamPercent), true);
        field(p + "daily", "日常比例（%）", String.valueOf(coach == null ? 10 : coach.dailyPercent), true);
        choice(p + "dream_slot", "分配到哪个梦想", dreamNames(), slotIds(), "0");
        dateField(p + "date", "分配日期", LocalDate.now());
        field(p + "note", "本次分配备注", "", false);
        action("预览这次分配", () -> validated(() -> {
            Transaction tx = selectedTransaction(p + "transaction", incomes);
            long[] values = split(tx.amountMinor, number(p + "goose"), number(p + "dream"), number(p + "daily"));
            new AlertDialog.Builder(this).setTitle("这笔收入的安排")
                    .setMessage("已确认收入 " + money(tx.amountMinor) + "\n长期积累 " + money(values[0])
                            + "\n" + dreamName(Integer.parseInt(selected(p + "dream_slot"))) + " " + money(values[1])
                            + "\n日常使用 " + money(values[2])).setPositiveButton("知道了", null).show();
        }), false);
        action("确认完整分配这笔收入", () -> validated(() -> {
            String txId = selectedTransaction(p + "transaction", incomes).id;
            int goose = number(p + "goose"), dream = number(p + "dream"), daily = number(p + "daily");
            split(1, goose, dream, daily);
            int slot = Integer.parseInt(selected(p + "dream_slot"));
            LocalDate date = actualDate(p + "date");
            String note = value(p + "note"), currentId = operationId;
            change(store -> store.allocateIncome(currentId, txId, goose, dream, daily, slot, date, note),
                    "收入已分配到钱罐", () -> { clearPrefix(p); operationId = id(); });
        }), true);
    }

    private void opening() {
        String p = potPrefix();
        heading("录入期初已有资金", "把开始使用钱罐前已有的钱记下来。这笔记录只增加钱罐余额，不会计成新收入；同一笔已有资金请只录入一次。");
        potChoice(p + "pot", "期初资金用途", "goose");
        field(p + "amount", "期初已有金额（元）", "", true);
        dateField(p + "date", "期初记录日期", LocalDate.now());
        field(p + "note", "期初资金来源或说明", "", false);
        action("保存期初资金", () -> validated(() -> {
            String pot = selected(p + "pot"), note = value(p + "note"), currentId = operationId;
            long amount = amount(p + "amount", false);
            LocalDate date = actualDate(p + "date");
            change(store -> store.addOpening(currentId, pot, amount, date, note), "期初资金已保存",
                    () -> { clearPrefix(p); operationId = id(); });
        }), true);
    }

    private void transfer() {
        String p = potPrefix();
        heading("调整钱罐用途", "在钱罐之间重新安排同一笔钱，总额不变，也不产生账本收入或支出。");
        potChoice(p + "from", "从哪个钱罐转出", "daily");
        potChoice(p + "to", "转入哪个钱罐", "goose");
        field(p + "amount", "调整用途金额（元）", "", true);
        dateField(p + "date", "调整用途日期", LocalDate.now());
        field(p + "note", "调整用途说明", "", false);
        action("保存用途调整", () -> validated(() -> {
            String from = selected(p + "from"), to = selected(p + "to"), note = value(p + "note"), currentId = operationId;
            if (from.equals(to)) throw new IllegalArgumentException("请选择两个不同的钱罐。");
            long amount = amount(p + "amount", false);
            LocalDate date = actualDate(p + "date");
            change(store -> store.transfer(currentId, from, to, amount, date, note), "钱罐用途已调整",
                    () -> { clearPrefix(p); operationId = id(); });
        }), true);
    }

    private void spend() {
        String p = potPrefix();
        heading("核销已记支出", "选择账本里已确认的一笔支出，从对应钱罐扣除。只核销钱罐余额，不会再次记一笔支出。");
        if (expenses.isEmpty()) { note("暂无未核销的已确认支出。先在账本或赚钱尝试中记录实际支出。"); return; }
        transactionChoice(p + "transaction", "选择要核销的支出", expenses);
        potChoice(p + "pot", "使用哪个钱罐", "daily");
        dateField(p + "date", "核销日期", LocalDate.now());
        field(p + "note", "核销说明", "", false);
        action("核销整笔支出", () -> validated(() -> {
            String txId = selectedTransaction(p + "transaction", expenses).id;
            String pot = selected(p + "pot"), note = value(p + "note"), currentId = operationId;
            LocalDate date = actualDate(p + "date");
            change(store -> store.coverExpense(currentId, txId, pot, date, note), "支出已从钱罐核销",
                    () -> { clearPrefix(p); operationId = id(); });
        }), true);
    }

    private void potHistory() {
        heading("钱罐明细", "撤销只影响这条钱罐操作，原账本收支仍保留。若后续使用了这笔钱，请先撤销相关的后续操作。");
        if (state.entries.isEmpty()) note("还没有钱罐记录。");
        int shown = 0;
        for (int index = state.entries.size() - 1; index >= 0 && shown < historyLimit; index--, shown++) {
            MoneyFinance.PotEntry entry = state.entries.get(index);
            String detail;
            if ("allocation".equals(entry.kind)) detail = "长期积累 " + money(entry.gooseMinor)
                    + "\n" + potName(entry.toPot) + " " + money(entry.dreamMinor) + "\n日常使用 " + money(entry.dailyMinor);
            else if ("transfer".equals(entry.kind)) detail = potName(entry.fromPot) + " → " + potName(entry.toPot) + "\n" + money(entry.amountMinor);
            else detail = potName("spend".equals(entry.kind) ? entry.fromPot : entry.toPot) + "  " + money(entry.amountMinor);
            card(entry.date + " · " + entryKind(entry.kind), detail + (entry.note.isEmpty() ? "" : "\n" + entry.note));
            action("撤销这条" + entryKind(entry.kind), () -> confirm("撤销这条钱罐操作？", "若撤销会让某个钱罐余额为负，操作会被阻止。",
                    () -> change(store -> store.deletePotEntry(entry.id), "钱罐操作已撤销", () -> {})), false);
        }
        more(shown, state.entries.size());
    }

    private void projects() {
        heading("靠解决需要创造收入", "先写清对方需要什么、你能提供什么。预计金额用于计划，实际到账与成本分别记录。");
        action("新建赚钱尝试", () -> { remember(); projectId = id(); flowId = id(); restoreScroll = 0; render(); }, false);
        if (!state.projects.isEmpty()) {
            String[] names = new String[state.projects.size() + 1];
            String[] ids = new String[names.length];
            names[0] = "新尝试草稿";
            ids[0] = findProject(projectId) == null ? projectId : id();
            int position = 0;
            for (int index = 0; index < state.projects.size(); index++) {
                MoneyFinance.Project existing = state.projects.get(index);
                names[index + 1] = existing.title + (existing.archived ? " · 已归档" : " · 进行中");
                ids[index + 1] = existing.id;
                if (existing.id.equals(projectId)) position = index + 1;
            }
            Spinner picker = spinner(names, position);
            picker.setContentDescription("选择赚钱尝试");
            body.addView(picker);
            picker.setOnItemSelectedListener(listener(index -> {
                if (ids[index].equals(projectId)) return;
                remember(); projectId = ids[index]; flowId = id(); restoreScroll = 0; render();
            }));
        }
        MoneyFinance.Project project = findProject(projectId);
        String p = "project_" + projectId + "_";
        heading(project == null ? "新的赚钱尝试" : "编辑这次尝试", "一次小尝试也可以。约定日期是你的计划，不会自动生成收入。");
        field(p + "title", "尝试名称", project == null ? "" : project.title, false);
        field(p + "need", "对方有什么需要", project == null ? "" : project.need, false);
        field(p + "offer", "我能提供的帮助或价值", project == null ? "" : project.offer, false);
        field(p + "resources", "可用的技能与资源", project == null ? "" : project.resources, false);
        field(p + "expected", "预计收入（元，可留空）", project == null ? "" : decimal(project.expectedMinor), true);
        dateField(p + "due", "约定日期（可留空）", project == null ? null : project.dueDate);
        field(p + "note", "尝试备注", project == null ? "" : project.note, false);
        choice(p + "archived", "尝试状态", new String[]{"进行中", "已归档"}, new String[]{"false", "true"}, String.valueOf(project != null && project.archived));
        action("保存赚钱尝试", () -> validated(() -> {
            MoneyFinance.Project changed = new MoneyFinance.Project(projectId, value(p + "title"), value(p + "need"),
                    value(p + "offer"), value(p + "resources"), amount(p + "expected", true), date(p + "due", true),
                    value(p + "note"), Boolean.parseBoolean(selected(p + "archived")));
            change(store -> store.saveProject(changed), "赚钱尝试已保存", () -> clearPrefix(p));
        }), true);
        if (project == null) { note("保存尝试后，可以分别记录实际收入与成本。"); return; }
        action("删除这次尝试", () -> confirm("删除这次尝试？", "已有实际流水的尝试需要先处理流水，或将尝试归档保留记录。",
                () -> change(store -> store.deleteProject(project.id), "尝试已删除", () -> { clearPrefix(p); projectId = id(); flowId = id(); })), false);
        card("实际收支", "实际收入 " + money(state.projectIncome(project.id)) + "\n实际成本 " + money(state.projectCost(project.id))
                + "\n净收入 " + money(state.projectNet(project.id)) + "\n预计收入 " + money(project.expectedMinor) + "（不计入现金）");
        projectFlows(project);
    }

    private void projectFlows(MoneyFinance.Project project) {
        String p = "flow_" + flowId + "_";
        MoneyFinance.ProjectFlow existing = null;
        for (MoneyFinance.ProjectFlow entry : state.flows) if (entry.id.equals(flowId)) existing = entry;
        heading(existing == null ? "记录实际收入或成本" : "编辑实际流水", project.archived
                ? "这次尝试已归档。将上方状态改为“进行中”并保存后，才能新增或修改实际流水。"
                : "保存后会同时进入当前账本。同一笔收支不要再在账本手动重复记录。已经用于钱罐的流水须先撤销对应钱罐操作，才能修改或删除。");
        choice(p + "kind", "实际流水类型", new String[]{"实际收入", "实际成本"}, new String[]{"income", "cost"}, existing == null ? "income" : existing.kind);
        field(p + "amount", "实际流水金额（元）", existing == null ? "" : decimal(existing.amountMinor), true);
        dateField(p + "date", "实际发生日期", existing == null ? LocalDate.now() : existing.date);
        field(p + "note", "实际流水说明", existing == null ? "" : existing.note, false);
        action("保存实际流水并记入账本", () -> validated(() -> {
            MoneyFinance.ProjectFlow changed = new MoneyFinance.ProjectFlow(flowId, project.id, selected(p + "kind"),
                    amount(p + "amount", false), actualDate(p + "date"), value(p + "note"));
            change(store -> store.saveFlow(changed), "实际流水与账本已保存", () -> { clearPrefix(p); flowId = id(); });
        }), true);
        if (existing != null) action("取消编辑，新增另一笔", () -> { remember(); flowId = id(); render(); }, false);
        int shown = 0, total = 0;
        for (int index = state.flows.size() - 1; index >= 0; index--) {
            MoneyFinance.ProjectFlow flow = state.flows.get(index);
            if (!flow.projectId.equals(project.id)) continue;
            total++;
            if (shown++ >= historyLimit) continue;
            card(flow.date + " · " + ("income".equals(flow.kind) ? "实际收入" : "实际成本"), money(flow.amountMinor)
                    + (flow.note.isEmpty() ? "" : "\n" + flow.note));
            action("编辑这笔" + ("income".equals(flow.kind) ? "收入" : "成本"), () -> {
                remember(); flowId = flow.id; render();
            }, false);
            action("删除这笔流水及账本记录", () -> confirm("删除这笔实际流水？", "这笔流水对应的账本记录也会删除。已用于钱罐时会阻止删除。",
                    () -> change(store -> store.deleteFlow(flow.id), "实际流水已删除", () -> { clearPrefix("flow_" + flow.id + "_"); if (flowId.equals(flow.id)) flowId = id(); })), false);
        }
        if (total == 0) note("还没有实际流水。预计收入不会自动出现在这里。");
        more(Math.min(shown, historyLimit), total);
    }

    private void items() {
        heading("看清拥有和欠下的", "这是你手动核对的资产负债快照。与钱罐、账本分别记录，不相加，也不会自动随消费更新。");
        card("资产负债快照", "流动现金 " + money(state.cashMinor) + "\n其他资产 " + money(state.assetMinor)
                + "\n债务余额 " + money(state.debtMinor) + "\n净资产 " + money(state.netWorthMinor));
        note("净资产 = 流动现金 + 其他资产 − 债务。净资产不等于现在可以花的钱。");
        action("新增现金、资产或债务", () -> { remember(); itemId = id(); restoreScroll = 0; render(); }, false);
        MoneyFinance.Item item = findItem(itemId);
        String p = "item_" + itemId + "_";
        heading(item == null ? "新增快照条目" : "编辑快照条目", "例如银行卡余额、自有物品估值或待还借款。还款信息按你的合同填写，不会生成借款或投资建议。");
        choice(p + "kind", "快照条目类型", new String[]{"流动现金", "其他资产", "债务"}, new String[]{"cash", "asset", "debt"}, item == null ? "cash" : item.kind);
        field(p + "title", "快照条目名称", item == null ? "" : item.title, false);
        field(p + "amount", "当前金额或余额（元）", item == null ? "" : decimal(item.amountMinor), true);
        dateField(p + "due", "债务到期日（非债务留空）", item == null ? null : item.dueDate);
        field(p + "minimum", "合同要求的本期还款（元，非债务留空）", item == null || item.minPaymentMinor == 0 ? "" : decimal(item.minPaymentMinor), true);
        field(p + "note", "快照说明与核对日期", item == null ? "" : item.note, false);
        action("保存资产债务条目", () -> validated(() -> {
            String kind = selected(p + "kind");
            LocalDate due = date(p + "due", true);
            long minimum = amount(p + "minimum", true);
            if (!"debt".equals(kind) && (due != null || minimum != 0))
                throw new IllegalArgumentException("现金和其他资产不填写还款信息，请清空到期日和本期还款。");
            MoneyFinance.Item changed = new MoneyFinance.Item(itemId, kind, value(p + "title"),
                    amount(p + "amount", true), due, minimum, value(p + "note"));
            change(store -> store.saveItem(changed), "资产债务条目已保存", () -> { clearPrefix(p); itemId = id(); });
        }), true);
        int shown = 0;
        for (MoneyFinance.Item entry : state.items) {
            if (shown++ >= historyLimit) break;
            card(entry.title + " · " + itemKind(entry.kind), money(entry.amountMinor)
                    + (entry.dueDate == null ? "" : "\n到期 " + entry.dueDate)
                    + (entry.minPaymentMinor == 0 ? "" : "\n合同要求的本期还款 " + money(entry.minPaymentMinor))
                    + (entry.note.isEmpty() ? "" : "\n" + entry.note));
            action("编辑“" + entry.title + "”", () -> { remember(); itemId = entry.id; restoreScroll = 0; render(); }, false);
            action("删除“" + entry.title + "”", () -> confirm("删除这个快照条目？", "只删除资产负债快照中的这一项，不会删除账本收支或钱罐记录。",
                    () -> change(store -> store.deleteItem(entry.id), "快照条目已删除", () -> { clearPrefix("item_" + entry.id + "_"); if (itemId.equals(entry.id)) itemId = id(); })), false);
        }
        more(Math.min(shown, historyLimit), state.items.size());
    }

    private MoneyFinance.Project findProject(String id) {
        for (MoneyFinance.Project project : state.projects) if (project.id.equals(id)) return project;
        return null;
    }

    private MoneyFinance.Item findItem(String id) {
        for (MoneyFinance.Item item : state.items) if (item.id.equals(id)) return item;
        return null;
    }

    private void transactionChoice(String key, String label, List<Transaction> records) {
        String[] labels = new String[records.size()], values = new String[records.size()];
        for (int index = 0; index < records.size(); index++) {
            Transaction tx = records.get(index);
            labels[index] = Instant.ofEpochMilli(tx.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    + " · " + tx.counterparty + " · " + money(Math.abs(tx.amountMinor));
            values[index] = tx.id;
        }
        choice(key, label, labels, values, values[0]);
    }

    private Transaction selectedTransaction(String key, List<Transaction> records) {
        String id = selected(key);
        for (Transaction tx : records) if (tx.id.equals(id)) return tx;
        throw new IllegalArgumentException("请选择一笔可用的已确认收支。");
    }

    private String[] slotIds() {
        String[] values = new String[10];
        for (int slot = 0; slot < 10; slot++) values[slot] = String.valueOf(slot);
        return values;
    }

    private String[] dreamNames() {
        String[] values = new String[10];
        for (int slot = 0; slot < 10; slot++) values[slot] = dreamName(slot);
        return values;
    }

    private String dreamName(int slot) {
        String title = coach == null ? "" : coach.wishes.get(slot).title;
        return "梦想 " + (slot + 1) + (title.isEmpty() ? " · 尚未命名" : " · " + title);
    }

    private void potChoice(String key, String label, String initial) {
        String[] names = new String[12], values = new String[12];
        names[0] = "长期积累"; values[0] = "goose";
        names[1] = "日常使用"; values[1] = "daily";
        for (int slot = 0; slot < 10; slot++) { names[slot + 2] = dreamName(slot); values[slot + 2] = "dream_" + slot; }
        choice(key, label, names, values, initial);
    }

    private String potName(String pot) {
        if ("goose".equals(pot)) return "长期积累";
        if ("daily".equals(pot)) return "日常使用";
        if (pot != null && pot.matches("dream_[0-9]")) return dreamName(Integer.parseInt(pot.substring(6)));
        return "";
    }

    private static String entryKind(String kind) {
        if ("opening".equals(kind)) return "期初资金";
        if ("allocation".equals(kind)) return "收入分配";
        if ("transfer".equals(kind)) return "用途调整";
        return "支出核销";
    }

    private static String itemKind(String kind) {
        return "cash".equals(kind) ? "流动现金" : "asset".equals(kind) ? "其他资产" : "债务";
    }

    private interface Position { void changed(int position); }

    private AdapterView.OnItemSelectedListener listener(Position callback) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!busy) callback.changed(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }

    private void choice(String key, String label, String[] names, String[] values, String initial) {
        body.addView(text(label, 13, MUTED, true));
        String current = drafts.getString(key, initial);
        int position = 0;
        for (int index = 0; index < values.length; index++) if (values[index].equals(current)) position = index;
        drafts.putString(key, values[position]);
        Spinner select = spinner(names, position);
        select.setContentDescription(label);
        body.addView(select);
        select.setOnItemSelectedListener(listener(index -> drafts.putString(key, values[index])));
    }

    private Spinner spinner(String[] values, int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(50));
        spinner.setBackground(tint(Color.WHITE, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        spinner.setLayoutParams(params);
        return spinner;
    }

    private void field(String key, String label, String initial, boolean numeric) {
        body.addView(text(label, 13, MUTED, true));
        EditText field = new EditText(this);
        field.setContentDescription(label);
        field.setText(drafts.getString(key, initial));
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setMinHeight(dp(48));
        field.setBackground(tint(Color.WHITE, 10));
        field.setSaveEnabled(false);
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(numeric ? 22 : 4000)});
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        body.addView(field, params);
        inputs.put(key, field);
    }

    private void dateField(String key, String label, LocalDate initial) {
        field(key, label, initial == null ? "" : initial.toString(), false);
        inputs.get(key).setHint("YYYY-MM-DD");
        inputs.get(key).setSingleLine(true);
        inputs.get(key).setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
    }

    private void remember() {
        for (Map.Entry<String, EditText> entry : inputs.entrySet()) drafts.putString(entry.getKey(), entry.getValue().getText().toString());
    }

    private String value(String key) {
        EditText field = inputs.get(key);
        return (field == null ? drafts.getString(key, "") : field.getText().toString()).trim();
    }

    private String selected(String key) { return drafts.getString(key, ""); }

    private long amount(String key, boolean allowZero) {
        String input = value(key);
        if (input.isEmpty() && allowZero) return 0;
        if (!input.matches("\\d{1,12}(\\.\\d{1,2})?")) throw new IllegalArgumentException("金额请填写最多两位小数的非负数字。");
        long value;
        try { value = new BigDecimal(input).movePointRight(2).longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("金额超出可记录范围。"); }
        if ((!allowZero && value == 0) || value > Transaction.MAX_ABS_AMOUNT_MINOR)
            throw new IllegalArgumentException(allowZero ? "金额超出可记录范围。" : "金额需要大于 0，且在可记录范围内。");
        return value;
    }

    private int number(String key) {
        String input = value(key);
        if (!input.matches("\\d{1,3}")) throw new IllegalArgumentException("比例请填写 0 到 100 的整数，三项合计 100。");
        int result = Integer.parseInt(input);
        if (result > 100) throw new IllegalArgumentException("比例请填写 0 到 100 的整数，三项合计 100。");
        return result;
    }

    private LocalDate date(String key, boolean optional) {
        String input = value(key);
        if (input.isEmpty() && optional) return null;
        try {
            LocalDate day = LocalDate.parse(input);
            if (!day.toString().equals(input) || day.isBefore(LocalDate.of(2000, 1, 1)) || !day.isBefore(LocalDate.of(2100, 1, 1)))
                throw new IllegalArgumentException();
            return day;
        } catch (RuntimeException error) { throw new IllegalArgumentException("日期请填写 2000 至 2099 年间的有效日期，格式 YYYY-MM-DD。"); }
    }

    private LocalDate actualDate(String key) {
        LocalDate date = date(key, false);
        if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("实际发生日期不能晚于今天。尚未收到的钱请记录为预计收入。");
        return date;
    }

    private static long[] split(long amount, int goose, int dream, int daily) {
        MoneyCoach.Allocation allocation = MoneyCoach.allocate(amount, goose, dream, daily);
        return new long[]{allocation.gooseMinor, allocation.dreamMinor, allocation.dailyMinor};
    }

    private static String decimal(long minor) { return BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
    private static String money(long minor) { return "¥ " + decimal(minor); }
    private static String id() { return UUID.randomUUID().toString(); }

    private void clearPrefix(String prefix) {
        for (String key : new ArrayList<>(drafts.keySet())) if (key.startsWith(prefix)) drafts.remove(key);
    }

    private void more(int shown, int total) {
        if (total > shown) action("再显示 20 条", () -> { remember(); historyLimit = Math.min(20000, historyLimit + 20); restoreScroll = scroll.getScrollY(); render(); }, false);
    }

    private void validated(Runnable work) {
        if (busy) return;
        try { work.run(); }
        catch (IllegalArgumentException | ArithmeticException error) { error(safeMessage(error)); }
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        // Only expected validation messages are user-facing; no database paths or payloads.
        return message != null && message.matches("(?s).*[\\p{IsHan}].*") && message.length() < 300
                ? message : "输入内容不符合要求，请检查金额、日期和必填项。";
    }

    private void confirm(String title, String message, Runnable confirmed) {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("确认", (dialog, which) -> confirmed.run()).show();
    }

    private void error(String message) {
        new AlertDialog.Builder(this).setTitle("暂未保存").setMessage(message == null ? "请稍后重试。" : message)
                .setPositiveButton("继续填写", null).show();
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (rootView != null) enable(rootView, !value);
        if (ioStatus != null) { ioStatus.setText(message); ioStatus.setVisibility(value ? View.VISIBLE : View.GONE); }
    }

    private void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) enable(group.getChildAt(index), enabled);
        }
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    @Override public void onBackPressed() {
        if (busy) toast("正在保存或读取，请稍等完成后返回。");
        else super.onBackPressed();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable tint(int color, int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }

    private void heading(String title, String description) {
        TextView heading = text(title, 21, INK, true);
        heading.setPadding(0, dp(12), 0, dp(8)); body.addView(heading); note(description);
    }

    private void note(String value) {
        TextView view = text(value, 14, MUTED, false);
        view.setPadding(0, dp(4), 0, dp(14)); body.addView(view);
    }

    private void card(String title, String value) {
        LinearLayout card = column(); card.setBackground(tint(PALE, 16)); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(text(title, 16, GREEN, true));
        TextView detail = text(value, 16, INK, false); detail.setPadding(0, dp(8), 0, 0); card.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12); body.addView(card, params);
    }

    private void action(String label, Runnable callback, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : GREEN); button.setBackground(tint(primary ? GREEN : Color.WHITE, 12));
        button.setMinHeight(dp(48)); button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setOnClickListener(v -> { if (!busy) callback.run(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12); body.addView(button, params);
    }
}
