"""Exercise this project's disposable emulator, without touching the Windows desktop."""
import json
import csv
import io
from decimal import Decimal
import re
import subprocess
import sys
import time
from datetime import date, timedelta
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5554'
ARTIFACTS = ROOT / 'artifacts'
ARTIFACTS.mkdir(exist_ok=True)

def adb(*args, binary=False):
    data = subprocess.check_output([str(ADB), '-s', SERIAL, *args], stderr=subprocess.STDOUT)
    return data if binary else data.decode('utf-8', errors='replace')

def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/tallybook-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/tallybook-ui.xml'))

def find(text=None, resource=None, desc=None, timeout=18, scroll=False, klass=None):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        tree = dump()
        for node in tree.iter('node'):
            if ((text is None or node.attrib.get('text') == text)
                    and (resource is None or node.attrib.get('resource-id') == resource)
                    and (desc is None or node.attrib.get('content-desc') == desc)
                    and (klass is None or node.attrib.get('class') == klass)):
                return node
        if scroll:
            adb('shell', 'input', 'swipe', '540', '1620', '540', '900', '250')
        time.sleep(.3)
    raise AssertionError(f'UI element missing: {text or resource or desc}; texts=' + str(texts(tree)))

def texts(tree):
    return [n.attrib['text'] for n in tree.iter('node') if n.attrib.get('text')]

def tap(text=None, resource=None, desc=None, scroll=False):
    node = find(text, resource, desc, scroll=scroll)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def screenshot(name):
    (ARTIFACTS / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def fill(desc, value):
    node = find(desc=desc, scroll=True)
    tap(desc=desc)
    adb('shell', 'input', 'keyevent', '123')
    old = node.attrib.get('text', '')
    if old:
        adb('shell', 'input', 'keyevent', *(['67'] * len(old)))
    if value:
        adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', '4')

def export_csv(kind):
    tap('设置')
    tap('导出当前' + kind + '账本 CSV', scroll=True)
    filename = find(resource='android:id/title', klass='android.widget.EditText').attrib['text']
    assert filename.startswith('tallybook-') and filename.endswith('.csv')
    tap(resource='android:id/button1')
    find('CSV 已导出')
    tap('知道了')
    exported = adb('exec-out', 'cat', '/sdcard/Download/' + filename, binary=True)
    assert exported.startswith(b'\xef\xbb\xbf'), 'CSV must contain UTF-8 BOM'
    return exported, list(csv.DictReader(io.StringIO(exported.decode('utf-8-sig'))))

def main():
    assert 'tallybook_api35' in adb('emu', 'avd', 'name'), 'Only the disposable test emulator is allowed'
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1', 'Emulator must finish booting first'
    adb('shell', 'am', 'start', '-n', 'dev.tallybook.app/.MainActivity')
    today = date.fromisoformat(adb('shell', 'date', '+%F').strip())
    tap('真实账本')
    find('先安排这个月', scroll=True)
    screenshot('01-real-empty.png')
    print('UI: setting a real budget', flush=True)
    tap('设置生活费计划', scroll=True)
    fill('budget_start', today.isoformat())
    fill('budget_end', (today + timedelta(days=29)).isoformat())
    fill('budget_opening', '2000')
    fill('budget_fixed', '300')
    fill('budget_savings', '200')
    tap('保存计划')
    find('¥ 1500.00', scroll=True)
    print('UI: saving a manual expense', flush=True)
    tap('记一笔', scroll=True)
    fill('manual_amount', '25.50')
    fill('manual_title', 'UI-lunch')
    fill('manual_category', 'Food')
    tap('保存记录')
    find('¥ 1474.50', scroll=True)
    find('¥ 49.15', scroll=True)
    screenshot('05-budget-home.png')
    print('UI: verifying trial does not change the ledger', flush=True)
    tap('消费试算', scroll=True)
    fill('trial_amount', '100')
    tap('计算影响')
    assert '1374.50' in find(desc='trial_result').attrib['text']
    tap('取消')
    find('¥ 1474.50', scroll=True)
    tap('梦想')
    tap('原存钱目标', scroll=True)
    print('UI: saving a goal and checking restart persistence', flush=True)
    tap('设置存钱目标', scroll=True)
    fill('goal_name', 'UI-trip')
    fill('goal_target', '1000')
    fill('goal_saved', '250')
    tap('保存目标')
    find('已存 ¥ 250.00')
    find(desc='存钱目标进度 25%')
    screenshot('06-savings-goal.png')
    adb('shell', 'am', 'force-stop', 'dev.tallybook.app')
    adb('shell', 'am', 'start', '-n', 'dev.tallybook.app/.MainActivity')
    find('¥ 1474.50', scroll=True)
    tap('梦想')
    tap('原存钱目标', scroll=True)
    find('已存 ¥ 250.00')
    _, real_rows = export_csv('真实')
    print('UI: real CSV exported; deleting the manual expense', flush=True)
    assert len(real_rows) == 1 and real_rows[0]['金额(元)'] == '-25.50'
    tap('账本')
    tap('UI-lunch', scroll=True)
    tap('删除这笔手动记录')
    tap('删除记录')
    find('0 笔')
    tap('今天')
    find('¥ 1500.00', scroll=True)
    tap('演示账本')
    print('UI: checking demo isolation and CSV', flush=True)
    tap('账本')
    find('4 笔')
    assert '¥ 60.80' in texts(dump()), 'Confirmed synthetic expenses should sum to 60.80 (refund excluded)'
    screenshot('02-demo-ledger.png')
    tap('设置')
    find('本机与数据')
    screenshot('04-settings.png')
    exported, rows = export_csv('演示')
    assert len(rows) == 4
    assert all(row['账本'] == '演示（虚构）' for row in rows)
    assert all(row['交易单号'].startswith("'") for row in rows), 'Transaction IDs must remain text in spreadsheets'
    expense = -sum(Decimal(r['金额(元)']) for r in rows if r['待核对'] == '否' and Decimal(r['金额(元)']) < 0)
    income = sum(Decimal(r['金额(元)']) for r in rows if r['待核对'] == '否' and Decimal(r['金额(元)']) > 0)
    assert (expense, income) == (Decimal('60.80'), Decimal('120.00'))
    (ARTIFACTS / 'demo-export.csv').write_bytes(exported)
    tap('账本')
    tap('真实账本')
    find('0 笔')
    tap('设置')
    assert '微信接口同步 · USB' not in texts(dump())
    tap('梦想')
    find('管理十个愿望与梦想图片', scroll=True)
    tap('成长')
    find('记录今日准则实践', scroll=True)
    tap('复盘')
    find('每月回顾与讨论', scroll=True)
    screenshot('07-review.png')
    result = {'passed': True, 'demo_records': len(rows), 'confirmed_expense': str(expense),
              'confirmed_income': str(income), 'real_records': 0, 'book_navigation': 'passed',
              'budget_manual_goal': 'passed', 'restart_persistence': 'passed',
              'spending_trial_no_write': 'passed', 'manual_deletion': 'passed',
              'live_wechat_tested': False, 'device': SERIAL}
    (ARTIFACTS / 'ui-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))

if __name__ == '__main__':
    main()
