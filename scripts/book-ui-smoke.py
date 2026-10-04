"""Targeted book-practice UI checks on tallybook_api35 only; no desktop input.

Uses fictional demo entries. Does not clear app data, install APKs, build, or touch a
physical device. Existing non-test wish slot 1 is protected. The app/test APKs must
already be installed. Results and screenshots remain in ignored artifacts/.
"""
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5554'
PACKAGE = 'dev.tallybook.app'
ARTIFACTS = ROOT / 'artifacts'
FIXTURE_DAY = date(2037, 2, 9)
TITLE = 'UI-book-dream'


def adb(*args, binary=False):
    output = subprocess.check_output([str(ADB), '-s', SERIAL, *args], stderr=subprocess.STDOUT)
    return output if binary else output.decode('utf-8', errors='replace')


def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/tallybook-book-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/tallybook-book-ui.xml'))


def dimensions():
    numbers = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
    assert numbers
    return tuple(map(int, numbers[-1]))


def swipe(up=True):
    width, height = dimensions()
    # Keep overlapping viewports: a fast fling can skip a short input entirely.
    start, end = (0.70, 0.48) if up else (0.36, 0.82)
    adb('shell', 'input', 'swipe', str(width // 2), str(int(height * start)),
        str(width // 2), str(int(height * end)), '600' if up else '220')


def find(text=None, desc=None, klass=None, timeout=18, scroll=False):
    deadline = time.monotonic() + timeout
    tree = None
    while time.monotonic() < deadline:
        tree = dump()
        for node in tree.iter('node'):
            if ((text is None or node.attrib.get('text') == text)
                    and (desc is None or node.attrib.get('content-desc') == desc)
                    and (klass is None or node.attrib.get('class') == klass)
                    and node.attrib.get('enabled') != 'false'):
                return node
        if scroll:
            swipe()
        time.sleep(0.2)
    visible = [node.attrib.get('text') for node in tree.iter('node') if node.attrib.get('text')]
    raise AssertionError(f'Missing UI element {text or desc or klass}: {visible}')


def tap(text=None, desc=None, scroll=False, klass=None):
    node = find(text=text, desc=desc, klass=klass, scroll=scroll)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    assert x2 > x1 and y2 > y1, node.attrib
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def fill(desc, value):
    node = find(desc=desc, scroll=True)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    adb('shell', 'input', 'keyevent', '123')
    old = node.attrib.get('text', '')
    if old:
        # These fixtures are short ASCII; never insert user text via a shell command.
        adb('shell', 'input', 'keyevent', *(['67'] * len(old)))
    if value:
        assert re.fullmatch(r'[A-Za-z0-9 .%_:/+\-]+', value), 'Only synthetic ASCII fixture input allowed'
        adb('shell', 'input', 'text', value.replace(' ', '%s'))
    adb('shell', 'input', 'keyevent', '4')


def top():
    previous = None
    for _ in range(12):
        tree = dump()
        current = ET.tostring(tree)
        if current == previous:
            break
        previous = current
        if any(n.attrib.get('class') == 'android.widget.ScrollView'
               and n.attrib.get('scrollable') == 'true' for n in tree.iter('node')):
            swipe(False)
        else:
            break


def page(label):
    tap(desc='选择钱钱练习页面')
    tap(text=label, scroll=True)


def state(source):
    raw = adb('exec-out', 'run-as', PACKAGE, 'cat', f'shared_prefs/money_coach_{source}.xml')
    root = ET.fromstring(raw)
    entry = root.find("./string[@name='state']")
    assert entry is not None and entry.text
    return json.loads(entry.text)


def wait_state(predicate, source='demo', timeout=18):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = state(source)
        if predicate(last):
            return last
        time.sleep(0.25)
    raise AssertionError('Persisted synthetic state did not match expectation')


def screenshot(name):
    (ARTIFACTS / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def start_coach(kind):
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    tap(text=kind + '账本')
    tap(text='成长')
    tap(text='书中方法 · 练习指南', scroll=True)
    find(text='钱钱练习 · ' + ('演示' if kind == '演示' else '真实账本'))
    find(text='把方法用到自己的生活里')


def monthly_state(source):
    path = f'shared_prefs/monthly_review_{source}.xml'
    listing = adb('shell', 'run-as', PACKAGE, 'ls', 'shared_prefs')
    if f'monthly_review_{source}.xml' not in listing:
        return []
    xml = ET.fromstring(adb('exec-out', 'run-as', PACKAGE, 'cat', path))
    entry = xml.find("./string[@name='state']")
    return [] if entry is None else json.loads(entry.text)['records']


def wait_month(predicate, source='demo', timeout=18):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = monthly_state(source)
        if predicate(current):
            return current
        time.sleep(0.25)
    raise AssertionError('Monthly synthetic state did not match expectation')


def visible_contains(*parts):
    values = [n.attrib.get('text', '') for n in dump().iter('node')]
    assert any(all(part in value for part in parts) for value in values), parts


def experiment(label):
    top()
    tap(desc='选择学习实验')
    tap(text=label, scroll=True)


def main():
    assert 'tallybook_api35' in adb('emu', 'avd', 'name'), 'Only the disposable tallybook_api35 AVD is allowed'
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1'
    ARTIFACTS.mkdir(exist_ok=True)
    original_rotation = adb('shell', 'settings', 'get', 'system', 'user_rotation').strip()
    original_accel = adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').strip()
    checks = []
    try:
        start_coach('真实')
        real_before = state('wechat')
        real_month_before = monthly_state('wechat')
        start_coach('演示')
        old_demo = state('demo')
        assert old_demo['wishes'][0]['title'] in ('', TITLE), 'Protect an existing non-test wish'
        assert not any(w['priority'] for w in old_demo['wishes'][1:]), 'Use an empty/demo-only practice fixture'
        page('愿望与梦想')
        fill('愿望', TITLE)
        fill('为什么它对我重要', 'A meaningful personal project')
        priority = find(text='选为当前重点（最多三个）', scroll=True)
        if priority.attrib.get('checked') != 'true':
            tap(text='选为当前重点（最多三个）')
        fill('目标金额（元，可留空）', '1200')
        fill('已存金额（元，手动记录）', '250')
        fill('希望完成日期（可留空）', (FIXTURE_DAY + timedelta(days=90)).isoformat())
        tap(text='选择梦想图片', scroll=True)
        adb('shell', 'input', 'keyevent', '4')
        find(text='保存这个愿望', scroll=True)
        tap(text='保存这个愿望')
        wait_state(lambda s: s['wishes'][0]['title'] == TITLE and s['wishes'][0]['savedMinor'] == 25000 and s['wishes'][0]['priority'])
        checks.append('wish_save_and_image_picker_cancel_preserve_form')
        print('book-ui-smoke: ' + str('wish_save_and_image_picker_cancel_preserve_form'), flush=True)

        page('成功日记')
        fill('记录日期', FIXTURE_DAY.isoformat())
        tap(text='打开这个日期的记录', scroll=True)
        fill('成功小事 1', 'I completed one small task')
        fill('成功小事 2', 'I asked for useful feedback')
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
        time.sleep(1)
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        find(desc='成功小事 1')
        assert find(desc='成功小事 1').attrib.get('text') == 'I completed one small task'
        tap(text='保存日记 / 草稿', scroll=True)
        wait_state(lambda s: any(e['date'] == FIXTURE_DAY.isoformat() and len(e['items']) == 2 for e in s['successes']))
        checks.append('success_two_items_saved_and_rotation_draft')
        print('book-ui-smoke: ' + str('success_two_items_saved_and_rotation_draft'), flush=True)

        page('分配历史演算')
        fill('这次可分配金额（元）', '74')
        fill('长期积累（%）', '50')
        fill('梦想（%）', '40')
        fill('日常（%）', '10')
        fill('这笔钱的来源或安排', 'UI-book-allocation')
        tap(text='计算分配金额', scroll=True)
        find(text='长期积累 ¥ 37.00\n梦想 ¥ 29.60\n日常 ¥ 7.40', scroll=True)
        tap(text='保存分配计划', scroll=True)
        wait_state(lambda s: any(r['note'] == 'UI-book-allocation' and r['executedAt'] == 0 for r in s['allocations']))
        tap(text='记录为已自行执行', scroll=True)
        tap(text='确认')
        wait_state(lambda s: any(r['note'] == 'UI-book-allocation' and r['executedAt'] > 0 for r in s['allocations']))
        checks.append('allocation_7400_to_3700_2960_740_plan_then_executed')
        print('book-ui-smoke: ' + str('allocation_7400_to_3700_2960_740_plan_then_executed'), flush=True)

        page('72 小时行动')
        fill('准备完成什么', 'UI-book-request-feedback')
        fill('实际结果 / 阻碍 / 下一步', 'I asked one person and recorded their feedback')
        tap(text='开始这个小行动', scroll=True)
        wait_state(lambda s: any(a['title'] == 'UI-book-request-feedback' and a['completedAt'] == 0 for a in s['actions']))
        tap(text='记录为已完成', scroll=True)
        wait_state(lambda s: any(a['title'] == 'UI-book-request-feedback' and a['completedAt'] > 0 for a in s['actions']))
        checks.append('action_saved_then_completed_with_result')
        print('book-ui-smoke: ' + str('action_saved_then_completed_with_result'), flush=True)

        page('每日准则')
        fill('练习日期', FIXTURE_DAY.isoformat())
        tap(text='打开这一天的准则', scroll=True)
        fill('这条对我意味着什么', 'Notice one useful strength')
        fill('真实经历与下一次想试的变化', 'I listened before replying')
        tap(text='保存理解与经历', scroll=True)
        wait_state(lambda s: any(p['date'] == FIXTURE_DAY.isoformat() and p['themeIndex'] == FIXTURE_DAY.weekday() for p in s['practices']))
        checks.append('practice_weekday_and_experience')
        print('book-ui-smoke: ' + str('practice_weekday_and_experience'), flush=True)

        page('每周复盘')
        fill('选择这一周中的任意日期', '2100-01-01')
        tap(text='打开这一周', scroll=True)
        find(text='请检查填写内容')
        tap(text='知道了')
        page('今天')
        page('每周复盘')
        fill('选择这一周中的任意日期', FIXTURE_DAY.isoformat())
        tap(text='打开这一周', scroll=True)
        fill('1. 这周有什么进展，哪件事值得继续？', 'I kept two useful records')
        fill('2. 什么阻碍了我，哪些条件能调整？', 'I planned too much')
        fill('3. 下周只改一件事，会是什么？', 'Choose one smaller action')
        tap(text='保存这周的回顾', scroll=True)
        monday = (FIXTURE_DAY - timedelta(days=FIXTURE_DAY.weekday())).isoformat()
        wait_state(lambda s: any(r['weekStart'] == monday and r['nextStep'] == 'Choose one smaller action' for r in s['reviews']))
        checks.append('weekly_review_and_out_of_range_date_navigation')
        print('book-ui-smoke: ' + str('weekly_review_and_out_of_range_date_navigation'), flush=True)

        tap(text='把下一步带到行动草稿', scroll=True)
        assert find(desc='准备完成什么').attrib.get('text') == 'Choose one smaller action'
        assert not any(a['title'] == 'Choose one smaller action' and a['completedAt'] == 0 for a in state('demo')['actions'])
        checks.append('weekly_next_step_opens_unsaved_action_draft')
        print('book-ui-smoke: ' + str('weekly_next_step_opens_unsaved_action_draft'), flush=True)

        page('月度回顾')
        fill('回顾月份', '2037-02')
        tap(text='打开这个月', scroll=True)
        fill('1. 钱、梦想与生活发生了什么变化？', 'UI-month-verified-facts')
        fill('2. 哪种做法有效，我学到了什么？', 'Compare received money with expectations')
        fill('3. 下个月想保留或调整什么？', 'Ask one focused question')
        tap(text='保存这个月的回顾', scroll=True)
        wait_month(lambda records: any(r['month'] == '2037-02' and r['facts'] == 'UI-month-verified-facts' for r in records))
        tap(text='把月度下一步带到行动草稿', scroll=True)
        assert find(desc='准备完成什么').attrib.get('text') == 'Ask one focused question'
        page('月度回顾')
        tap(text='删除 2037-02 回顾', scroll=True)
        tap(text='确认')
        wait_month(lambda records: not any(r['month'] == '2037-02' for r in records))
        checks.append('monthly_save_prefill_delete')
        print('book-ui-smoke: ' + str('monthly_save_prefill_delete'), flush=True)

        page('学习试算')
        fill('假设本金（元）', '3000')
        fill('假设年变化率（%，可为负）', '3')
        fill('年数（0—100 的整数）', '2')
        tap(text='计算这个假设', scroll=True)
        assert any('3182.70' in n.attrib.get('text', '') for n in dump().iter('node'))
        top()
        fill('假设年变化率（%，可为负）', '-5')
        tap(text='计算这个假设', scroll=True)
        assert any('2707.50' in n.attrib.get('text', '') and '不适用' in n.attrib.get('text', '') for n in dump().iter('node'))
        screenshot('book-learning-negative.png')
        checks.append('compound_positive_negative_and_72_boundary')
        print('book-ui-smoke: ' + str('compound_positive_negative_and_72_boundary'), flush=True)

        top()
        fill('假设年变化率（%，可为负）', '0')
        fill('年数（0—100 的整数）', '1')
        fill('每期末追加（元，可留空）', '100')
        tap(desc='选择追加频率', scroll=True)
        tap(text='每月末追加')
        fill('假设年通胀率（%，可为负）', '5')
        tap(text='计算这个假设', scroll=True)
        visible_contains('4200.00', '4000.00', '每月末追加')
        checks.append('monthly_addition_and_inflation_purchasing_power')
        print('book-ui-smoke: ' + str('monthly_addition_and_inflation_purchasing_power'), flush=True)

        experiment('集中、分散与共同下跌')
        fill('假设总金额（元）', '20000')
        tap(text='比较三个场景', scroll=True)
        visible_contains('19600.00', '12000.00', '共同下跌')
        checks.append('twenty_holdings_single_and_common_decline')
        print('book-ui-smoke: ' + str('twenty_holdings_single_and_common_decline'), flush=True)

        experiment('消费债务余款示例')
        fill('假设已到账金额（元）', '100')
        fill('假设必要开支（元）', '90')
        fill('假设合同要求本期支付（元）', '20')
        tap(text='观察余款示例', scroll=True)
        visible_contains('缺口', '10.00', '没有可供平分的余款')
        checks.append('debt_remainder_respects_obligations_and_shortfall')
        print('book-ui-smoke: ' + str('debt_remainder_respects_obligations_and_shortfall'), flush=True)

        page('方法库')
        find(text='钱对我的意义')
        screenshot('book-method-library.png')
        assert state('wechat') == real_before, 'Demo exercises must not write real practice data'
        assert monthly_state('wechat') == real_month_before, 'Demo monthly reviews must not write real reviews'
        checks.append('real_demo_practice_isolation')
        print('book-ui-smoke: ' + str('real_demo_practice_isolation'), flush=True)
        start_coach('演示')
        page('愿望与梦想')
        assert find(desc='愿望').attrib.get('text') == TITLE
        screenshot('book-dream.png')
        checks.append('restart_persistence')
        print('book-ui-smoke: ' + str('restart_persistence'), flush=True)

        result = {'passed': True, 'checks': checks, 'device': SERIAL, 'avd': 'tallybook_api35',
                  'fictional_data': True, 'image_picker_cancel': True,
                  'image_selection_and_persisted_uri_tested': False,
                  'live_wechat_tested': False}
        (ARTIFACTS / 'book-ui-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        for key, value in [('user_rotation', original_rotation), ('accelerometer_rotation', original_accel)]:
            if value == 'null':
                adb('shell', 'settings', 'delete', 'system', key)
            else:
                adb('shell', 'settings', 'put', 'system', key, value)


if __name__ == '__main__':
    main()
