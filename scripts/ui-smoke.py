"""Exercise this project's disposable emulator, without touching the Windows desktop."""
import json
import csv
import io
from decimal import Decimal
import re
import subprocess
import sys
import time
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

def find(text=None, resource=None, timeout=15):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        tree = dump()
        for node in tree.iter('node'):
            if ((text is None or node.attrib.get('text') == text)
                    and (resource is None or node.attrib.get('resource-id') == resource)):
                return node
        time.sleep(.3)
    raise AssertionError(f'UI element missing: {text or resource}; texts=' + str(texts(tree)))

def texts(tree):
    return [n.attrib['text'] for n in tree.iter('node') if n.attrib.get('text')]

def tap(text=None, resource=None):
    node = find(text, resource)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def screenshot(name):
    (ARTIFACTS / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def main():
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1', 'Emulator must finish booting first'
    adb('shell', 'am', 'start', '-n', 'dev.tallybook.app/.MainActivity')
    find('真实账本')
    tap('真实账本')
    find('0 笔')
    screenshot('01-real-empty.png')
    tap('演示账本')
    find('4 笔')
    assert '¥ 60.80' in texts(dump()), 'Confirmed synthetic expenses should sum to 60.80 (refund excluded)'
    screenshot('02-demo-ledger.png')
    tap('采集')
    find('微信采集')
    screenshot('03-capture.png')
    tap('设置')
    find('本机与数据')
    screenshot('04-settings.png')
    tap('导出当前演示账本 CSV')
    filename = find(resource='android:id/title').attrib['text']
    assert filename.startswith('tallybook-demo-') and filename.endswith('.csv')
    tap(resource='android:id/button1')
    find('CSV 已导出')
    tap('知道了')
    exported = adb('exec-out', 'cat', '/sdcard/Download/' + filename, binary=True)
    assert exported.startswith(b'\xef\xbb\xbf'), 'CSV must contain UTF-8 BOM'
    rows = list(csv.DictReader(io.StringIO(exported.decode('utf-8-sig'))))
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
    tap('采集')
    tap('开启 30 分钟采集')
    find('开启查看页采集？')
    tap('开启 30 分钟')
    find('等待微信详情数据')
    tap('停止采集')
    find('采集已关闭')
    screenshot('03-capture.png')
    result = {'passed': True, 'demo_records': len(rows), 'confirmed_expense': str(expense),
              'confirmed_income': str(income), 'real_records': 0, 'capture_toggle': 'passed',
              'live_wechat_tested': False, 'device': SERIAL}
    (ARTIFACTS / 'ui-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))

if __name__ == '__main__':
    main()
