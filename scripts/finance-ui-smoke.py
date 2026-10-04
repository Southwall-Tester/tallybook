"""Exercise real finance-practice forms using fictional demo data on one allowed AVD.

No APK build/install, app-data reset, or physical-device operation. A run uses a
unique project title and leaves its fictional records visible for inspection.
Read-only database copies and screenshots stay in ignored artifacts/.
"""
import importlib.util
import json
import sqlite3
import subprocess
import tempfile
import time
from contextlib import closing
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('book_ui_helpers', ROOT / 'scripts/book-ui-smoke.py')
UI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UI)
adb, tap, find, fill = UI.adb, UI.tap, UI.find, UI.fill
ARTIFACTS = ROOT / 'artifacts'
FIXTURE_DAY = date(2025, 2, 11)
PLAN_DAY = date(2037, 2, 11)
RUN = str(int(time.time()))
TITLE = 'UI-finance-' + RUN


def snapshot():
    """Copy only this disposable AVD's local database; never run SQL on the device."""
    with tempfile.TemporaryDirectory(prefix='finance-ui-', dir=ARTIFACTS) as temporary:
        folder = Path(temporary)
        assert folder.resolve().is_relative_to(ARTIFACTS.resolve()), 'Temporary cleanup stays inside test artifacts'
        for name in ('tallybook.db', 'tallybook.db-wal'):
            try:
                raw = adb('exec-out', 'run-as', UI.PACKAGE, 'cat', 'databases/' + name, binary=True)
            except subprocess.CalledProcessError:
                if name.endswith('-wal'):
                    continue
                raise
            (folder / name).write_bytes(raw)
        with closing(sqlite3.connect(folder / 'tallybook.db')) as database:
            finance = {source: json.loads(payload) for source, payload in database.execute(
                'SELECT source, payload FROM finance_states')}
            ledger = {source: [] for source in ('wechat', 'demo')}
            for source, payload in database.execute('SELECT source, payload FROM transactions ORDER BY transaction_id'):
                ledger[source].append(json.loads(payload))
        return finance, ledger


def state(source='demo'):
    return snapshot()[0].get(source, {'schemaVersion': 1, 'entries': [], 'projects': [], 'flows': [], 'items': []})


def wait_state(predicate, timeout=20):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        current = state()
        if predicate(current):
            return current
        time.sleep(0.3)
    raise AssertionError('Synthetic finance state did not match the expected result')


def pick(description, label):
    tap(desc=description, scroll=True)
    tap(text=label, scroll=True)


def page(label):
    pick('选择资金实践页面', label)


def pot_action(label):
    UI.top()
    pick('选择钱罐操作', label)


def start(kind='演示'):
    adb('shell', 'am', 'force-stop', UI.PACKAGE)
    adb('shell', 'am', 'start', '-n', UI.PACKAGE + '/.MainActivity')
    tap(text=kind + '账本')
    tap(text='管理我的钱罐', scroll=True)
    find(text='资金实践 · ' + ('演示' if kind == '演示' else '真实账本'))
    find(text='给手里的钱一个用途')


def assert_visible(fragment):
    for _ in range(14):
        if any(fragment in node.attrib.get('text', '') for node in UI.dump().iter('node')):
            return
        UI.swipe()
    raise AssertionError('Missing expected synthetic summary: ' + fragment)


def balance(current, pot):
    total = 0
    for entry in current['entries']:
        if entry['kind'] == 'opening' and entry['toPot'] == pot:
            total += entry['amountMinor']
        elif entry['kind'] == 'allocation':
            if pot == 'goose':
                total += entry['gooseMinor']
            elif pot == 'daily':
                total += entry['dailyMinor']
            elif pot == entry['toPot']:
                total += entry['dreamMinor']
        elif entry['kind'] == 'transfer':
            if entry['fromPot'] == pot:
                total -= entry['amountMinor']
            if entry['toPot'] == pot:
                total += entry['amountMinor']
        elif entry['kind'] == 'spend' and entry['fromPot'] == pot:
            total -= entry['amountMinor']
    return total


def main():
    assert UI.SERIAL == 'emulator-5554'
    assert 'tallybook_api35' in adb('emu', 'avd', 'name'), 'Only the disposable tallybook_api35 AVD is allowed'
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1'
    ARTIFACTS.mkdir(exist_ok=True)
    rotation = adb('shell', 'settings', 'get', 'system', 'user_rotation').strip()
    accelerometer = adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').strip()
    checks = []
    try:
        start('真实')
        real_before = state('wechat')
        real_ledger_before = snapshot()[1]['wechat']
        start()
        page('赚钱尝试')
        fill('尝试名称', TITLE)
        fill('对方有什么需要', 'A classmate needs a tidy study desk')
        fill('我能提供的帮助或价值', 'Organize a desk together')
        fill('可用的技能与资源', 'Time and organizing skills')
        fill('预计收入（元，可留空）', '200')
        fill('约定日期（可留空）', PLAN_DAY.isoformat())
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
        time.sleep(1)
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        UI.top()
        assert find(desc='尝试名称', scroll=True).attrib.get('text') == TITLE
        tap(text='保存赚钱尝试', scroll=True)
        current = wait_state(lambda s: any(p['title'] == TITLE for p in s['projects']))
        project = next(p for p in current['projects'] if p['title'] == TITLE)
        assert project['expectedMinor'] == 20000
        assert project['dueDate'] == PLAN_DAY.isoformat()
        assert not any(f['projectId'] == project['id'] for f in current['flows'])
        assert not any(tx['counterparty'] == TITLE for tx in snapshot()[1]['demo'])
        checks.append('project_draft_survives_rotation_expected_income_not_cash')
        print('finance-ui-smoke: ' + str('project_draft_survives_rotation_expected_income_not_cash'), flush=True)

        fill('实际流水金额（元）', '123.45')
        fill('实际发生日期', FIXTURE_DAY.isoformat())
        fill('实际流水说明', 'Received fictional project income')
        tap(text='保存实际流水并记入账本', scroll=True)
        wait_state(lambda s: any(f['projectId'] == project['id'] and f['kind'] == 'income' for f in s['flows']))
        pick('实际流水类型', '实际成本')
        fill('实际流水金额（元）', '20')
        fill('实际发生日期', FIXTURE_DAY.isoformat())
        fill('实际流水说明', 'Fictional material cost')
        tap(text='保存实际流水并记入账本', scroll=True)
        current = wait_state(lambda s: len([f for f in s['flows'] if f['projectId'] == project['id']]) == 2)
        actual = [tx for tx in snapshot()[1]['demo'] if tx['counterparty'] == TITLE]
        assert sorted(tx['amountMinor'] for tx in actual) == [-2000, 12345]
        assert_visible('净收入 ¥ 103.45')
        UI.screenshot('finance-project-net.png')
        checks.append('actual_income_cost_atomically_in_ledger_net_10345')
        print('finance-ui-smoke: ' + str('actual_income_cost_atomically_in_ledger_net_10345'), flush=True)

        page('钱罐')
        pick('选择要分配的收入', f'{FIXTURE_DAY.isoformat()} · {TITLE} · ¥ 123.45')
        fill('长期积累比例（%）', '50')
        fill('梦想比例（%）', '40')
        fill('日常比例（%）', '10')
        fill('本次分配备注', TITLE + '-allocate')
        tap(text='预览这次分配', scroll=True)
        assert_visible('长期积累 ¥ 61.73')
        tap(text='知道了')
        tap(text='确认完整分配这笔收入', scroll=True)
        current = wait_state(lambda s: any(e['note'] == TITLE + '-allocate' for e in s['entries']))
        allocated = next(e for e in current['entries'] if e['note'] == TITLE + '-allocate')
        assert (allocated['gooseMinor'], allocated['dreamMinor'], allocated['dailyMinor']) == (6173, 4938, 1234)
        assert sum(allocated[k] for k in ('gooseMinor', 'dreamMinor', 'dailyMinor')) == 12345
        # A used income must disappear from choices instead of offering a second allocation.
        UI.top()
        nodes = list(UI.dump().iter('node'))
        if any(n.attrib.get('content-desc') == '选择要分配的收入' for n in nodes):
            tap(desc='选择要分配的收入', scroll=True)
            assert not any(TITLE in n.attrib.get('text', '') for n in UI.dump().iter('node'))
            adb('shell', 'input', 'keyevent', '4')
        checks.append('allocation_exact_cents_and_used_income_removed')
        print('finance-ui-smoke: ' + str('allocation_exact_cents_and_used_income_removed'), flush=True)

        pot_action('录入期初已有资金')
        pick('期初资金用途', '日常使用')
        fill('期初已有金额（元）', '10')
        fill('期初资金来源或说明', TITLE + '-opening')
        ledger_count = len(snapshot()[1]['demo'])
        tap(text='保存期初资金', scroll=True)
        wait_state(lambda s: any(e['note'] == TITLE + '-opening' for e in s['entries']))
        assert len(snapshot()[1]['demo']) == ledger_count
        pot_action('调整钱罐用途')
        fill('调整用途金额（元）', '1')
        fill('调整用途说明', TITLE + '-transfer')
        tap(text='保存用途调整', scroll=True)
        current = wait_state(lambda s: any(e['note'] == TITLE + '-transfer' for e in s['entries']))
        count_before = len(current['entries'])
        too_much_minor = balance(current, 'daily') + 1
        too_much = str(too_much_minor // 100) + '.' + str(too_much_minor % 100).zfill(2)
        fill('调整用途金额（元）', too_much)
        tap(text='保存用途调整', scroll=True)
        assert_visible('钱罐余额不足')
        tap(text='继续填写')
        assert find(desc='调整用途金额（元）', scroll=True).attrib.get('text') == too_much
        assert len(state()['entries']) == count_before
        checks.append('insufficient_balance_rejected_without_losing_draft')
        print('finance-ui-smoke: ' + str('insufficient_balance_rejected_without_losing_draft'), flush=True)
        pot_action('核销已记支出')
        pick('选择要核销的支出', f'{FIXTURE_DAY.isoformat()} · {TITLE} · ¥ 20.00')
        fill('核销说明', TITLE + '-spend')
        tap(text='核销整笔支出', scroll=True)
        wait_state(lambda s: any(e['note'] == TITLE + '-spend' for e in s['entries']))
        assert len(snapshot()[1]['demo']) == ledger_count
        checks.append('opening_transfer_and_spend_do_not_duplicate_ledger')
        print('finance-ui-smoke: ' + str('opening_transfer_and_spend_do_not_duplicate_ledger'), flush=True)

        page('资产债务')
        fill('快照条目名称', TITLE + '-cash')
        fill('当前金额或余额（元）', '100')
        tap(text='保存资产债务条目', scroll=True)
        wait_state(lambda s: any(i['title'] == TITLE + '-cash' for i in s['items']))
        pick('快照条目类型', '债务')
        fill('快照条目名称', TITLE + '-debt')
        fill('当前金额或余额（元）', '30')
        fill('债务到期日（非债务留空）', PLAN_DAY.isoformat())
        fill('合同要求的本期还款（元，非债务留空）', '5')
        tap(text='保存资产债务条目', scroll=True)
        current = wait_state(lambda s: any(i['title'] == TITLE + '-debt' for i in s['items']))
        debt = next(i for i in current['items'] if i['title'] == TITLE + '-debt')
        assert debt['amountMinor'] == 3000 and debt['minPaymentMinor'] == 500
        tap(text='编辑“' + TITLE + '-cash”', scroll=True)
        fill('当前金额或余额（元）', '150')
        tap(text='保存资产债务条目', scroll=True)
        wait_state(lambda s: any(i['title'] == TITLE + '-cash' and i['amountMinor'] == 15000 for i in s['items']))
        tap(text='删除“' + TITLE + '-cash”', scroll=True)
        tap(text='确认')
        wait_state(lambda s: not any(i['title'] == TITLE + '-cash' for i in s['items']))
        checks.append('cash_debt_snapshot_create_edit_delete_and_contract_fields')
        print('finance-ui-smoke: ' + str('cash_debt_snapshot_create_edit_delete_and_contract_fields'), flush=True)
        UI.top()
        UI.screenshot('finance-assets-debt.png')

        assert state('wechat') == real_before
        assert snapshot()[1]['wechat'] == real_ledger_before
        checks.append('demo_finance_and_ledger_isolated_from_real')
        print('finance-ui-smoke: ' + str('demo_finance_and_ledger_isolated_from_real'), flush=True)
        start()
        current = state()
        assert any(p['id'] == project['id'] for p in current['projects'])
        assert any(e['id'] == allocated['id'] for e in current['entries'])
        UI.screenshot('finance-money-pots.png')
        checks.append('restart_persistence')
        print('finance-ui-smoke: ' + str('restart_persistence'), flush=True)
        result = {'passed': True, 'checks': checks, 'device': UI.SERIAL, 'avd': 'tallybook_api35',
                  'fictional_data': True, 'live_wechat_tested': False, 'fixture_project': TITLE}
        (ARTIFACTS / 'finance-ui-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        for key, value in [('user_rotation', rotation), ('accelerometer_rotation', accelerometer)]:
            if value == 'null':
                adb('shell', 'settings', 'delete', 'system', key)
            else:
                adb('shell', 'settings', 'put', 'system', key, value)


if __name__ == '__main__':
    main()
