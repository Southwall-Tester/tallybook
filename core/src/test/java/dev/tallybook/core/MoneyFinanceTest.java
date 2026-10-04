package dev.tallybook.core;

import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MoneyFinanceTest {
    private static final LocalDate DAY = LocalDate.of(2026,10,4);
    private static final String INCOME = Transaction.manual("fiction-income","实际收入","项目","",101,1791115200000L).id;
    private static final String EXPENSE = Transaction.manual("fiction-expense","实际支出","项目","",-10,1791115200000L).id;
    private MoneyFinance.State entries(MoneyFinance.PotEntry... entries) {
        return new MoneyFinance.State(Arrays.asList(entries),Collections.emptyList(),Collections.emptyList(),Collections.emptyList());
    }
    private MoneyFinance.PotEntry opening(String id,long amount) { return new MoneyFinance.PotEntry(id,"opening","","","daily",amount,0,0,0,DAY,"期初已有资金"); }
    private MoneyFinance.Project project() { return new MoneyFinance.Project("project","提供帮助","具体需求","服务内容","已有能力",100000,DAY.plusDays(7),"这是预期",false); }

    @Test public void allocationAndTransfersConserveCentsWithoutInventingIncome() {
        MoneyCoach.Allocation a = MoneyCoach.allocate(101,50,40,10);
        MoneyFinance.PotEntry split = new MoneyFinance.PotEntry("allocation","allocation",INCOME,"","dream_0",101,a.gooseMinor,a.dreamMinor,a.dailyMinor,DAY,"");
        MoneyFinance.PotEntry transfer = new MoneyFinance.PotEntry("move","transfer","","goose","dream_0",1,0,0,0,DAY,"");
        MoneyFinance.State state = entries(split,transfer);
        assertEquals(50,state.balance("goose")); assertEquals(41,state.balance("dream_0")); assertEquals(10,state.balance("daily"));
        assertEquals(0,state.cashMinor); assertEquals(0,state.netWorthMinor);
        assertEquals(12,state.balances.size());
    }
    @Test public void replayRejectsOverdraftAndUndoThatWouldBreakLaterHistory() {
        MoneyFinance.PotEntry spend = new MoneyFinance.PotEntry("spend","spend",EXPENSE,"daily","",10,0,0,0,DAY,"");
        assertEquals(90,entries(opening("opening",100),spend).balance("daily"));
        assertThrows(IllegalArgumentException.class,() -> entries(spend));
        assertThrows(IllegalArgumentException.class,() -> entries(spend,opening("opening",100)));
        assertEquals(100,entries(opening("opening",100)).balance("daily"));
    }
    @Test public void sameLedgerRecordCannotBeAllocatedTwice() {
        MoneyFinance.PotEntry first = new MoneyFinance.PotEntry("first","allocation",INCOME,"","dream_0",101,51,40,10,DAY,"");
        MoneyFinance.PotEntry second = new MoneyFinance.PotEntry("second","allocation",INCOME,"","dream_1",101,51,40,10,DAY,"");
        assertThrows(IllegalArgumentException.class,() -> entries(first,second));
        assertThrows(IllegalArgumentException.class,() -> entries(first,first));
    }
    @Test public void malformedEventsCannotHideExtraMoneyOrSourceReferences() {
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.PotEntry("x","allocation",INCOME,"","dream_0",101,50,40,10,DAY,""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.PotEntry("x","opening",INCOME,"","daily",100,0,0,0,DAY,""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.PotEntry("x","transfer","","daily","daily",100,0,0,0,DAY,""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.PotEntry("x","opening","","","dream_10",100,0,0,0,DAY,""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.PotEntry("x","spend","fake","daily","",100,0,0,0,DAY,""));
    }
    @Test public void expectedIncomeNeverCountsAsActualAndCostsReduceProjectNet() {
        MoneyFinance.ProjectFlow received = new MoneyFinance.ProjectFlow("income","project","income",7400,DAY,"");
        MoneyFinance.ProjectFlow cost = new MoneyFinance.ProjectFlow("cost","project","cost",1400,DAY,"");
        MoneyFinance.State state = new MoneyFinance.State(Collections.emptyList(),Collections.singletonList(project()),Arrays.asList(received,cost),Collections.emptyList());
        assertEquals(7400,state.projectIncome("project")); assertEquals(1400,state.projectCost("project")); assertEquals(6000,state.projectNet("project"));
        assertEquals(0,state.balance("goose")); assertEquals(0,state.netWorthMinor);
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.State(Collections.emptyList(),Collections.emptyList(),Collections.singletonList(received),Collections.emptyList()));
    }
    @Test public void assetSnapshotSeparatesCashAndAllowsNegativeNetWorth() {
        List<MoneyFinance.Item> items = Arrays.asList(new MoneyFinance.Item("cash","cash","已有现金",1000,null,0,""),
                new MoneyFinance.Item("asset","asset","物品估值",2000,null,0,""),new MoneyFinance.Item("debt","debt","待还余额",5000,DAY.plusDays(10),500,"按账单填写"));
        MoneyFinance.State state = new MoneyFinance.State(Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),items);
        assertEquals(1000,state.cashMinor); assertEquals(2000,state.assetMinor); assertEquals(5000,state.debtMinor); assertEquals(-2000,state.netWorthMinor);
        assertEquals(0,state.balance("daily"));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.Item("bad","cash","现金",10,DAY,0,""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.Item("bad","debt","债务",10,DAY,11,""));
    }
    @Test public void allCollectionsAndDerivedBalancesAreImmutable() {
        MoneyFinance.State state = entries(opening("opening",1));
        assertThrows(UnsupportedOperationException.class,() -> state.entries.clear());
        assertThrows(UnsupportedOperationException.class,() -> state.balances.put("daily",100L));
    }
    @Test public void amountsDatesAndAggregateOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> entries(opening("zero",0)));
        assertThrows(IllegalArgumentException.class,() -> entries(opening("maximum",Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class,() -> entries(opening("first",MoneyFinance.MAX_AMOUNT_MINOR),opening("second",1)));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.ProjectFlow("x","project","income",1,LocalDate.of(2100,1,1),""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.Item("x","cash","现金",-1,null,0,""));
        assertThrows(IllegalArgumentException.class,() -> new MoneyFinance.Project("x","项目","","","",-1,null,"",false));
    }
    @Test public void allModelsRoundTripAndBalancesAreReplayed() {
        MoneyFinance.State state = new MoneyFinance.State(Collections.singletonList(opening("opening",100)),Collections.singletonList(project()),
                Collections.singletonList(new MoneyFinance.ProjectFlow("flow","project","cost",10,DAY,"成本\n细节")),
                Collections.singletonList(new MoneyFinance.Item("debt","debt","余额",200,DAY.plusDays(20),20,"")));
        MoneyFinance.State read = MoneyFinance.State.fromJson(state.toJson());
        assertEquals(100,read.balance("daily")); assertEquals(-10,read.projectNet("project")); assertEquals(100000,read.projects.get(0).expectedMinor);
        assertEquals("成本\n细节",read.flows.get(0).note); assertEquals(20,read.items.get(0).minPaymentMinor);
    }
    @Test public void strictJsonRejectsUnknownFieldsDuplicatesStringsFractionsAndTrailingData() throws Exception {
        String valid = entries(opening("opening",100)).toJson();
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(valid + "null"));
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")));
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(new JSONObject(valid).put("balance",100).toString()));
        JSONObject numericString = new JSONObject(valid); numericString.getJSONArray("entries").getJSONObject(0).put("amountMinor","100");
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(numericString.toString()));
        JSONObject fractional = new JSONObject(valid); fractional.getJSONArray("entries").getJSONObject(0).put("amountMinor",1.5);
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(fractional.toString()));
        JSONObject wrongDay = new JSONObject(valid); wrongDay.getJSONArray("entries").getJSONObject(0).put("date","2026-02-30");
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(wrongDay.toString()));
        assertThrows(IllegalArgumentException.class,() -> MoneyFinance.State.fromJson(new JSONObject(valid).put("schemaVersion",2).toString()));
    }
    @Test public void storageLimitKeepsTheSingleSqliteRowBelowCursorWindowCapacity() {
        List<MoneyFinance.Project> projects = new ArrayList<>();
        String text = "x".repeat(2000);
        for (int i = 0; i < 160; i++) projects.add(new MoneyFinance.Project("project_" + i,"计划",text,text,text,0,null,text,false));
        MoneyFinance.State large = new MoneyFinance.State(Collections.emptyList(),projects,Collections.emptyList(),Collections.emptyList());
        assertThrows(IllegalArgumentException.class,large::toJson);
        assertEquals(1024 * 1024,MoneyFinance.MAX_STATE_BYTES);
    }
}
