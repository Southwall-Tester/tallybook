import importlib.util
import json
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('smoke', root / 'scripts/ui-smoke.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
assert 'tallybook_api35' in m.adb('emu', 'avd', 'name')
m.adb('shell', 'am', 'start', '-n', 'dev.tallybook.app/.MainActivity')
m.tap('账本')
m.tap('记一笔')
m.fill('manual_amount', '1.00')
m.fill('manual_title', 'Refresh-check')
m.tap('保存记录')
m.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
try:
    m.adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    time.sleep(1)
finally:
    m.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    time.sleep(1)
m.tap('账本')
m.find('1 笔', scroll=True)
m.tap('Refresh-check', scroll=True)
m.tap('删除这笔手动记录')
m.tap('删除记录')
m.find('0 笔', scroll=True)
result = {'passed': True, 'manual_save_rotate_delete': True, 'device': m.SERIAL}
(m.ARTIFACTS / 'refresh-check.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result), flush=True)
