"""Verify a synthetic dream image and legacy-goal copy on tallybook_api35 only.

Requires the installed v0.5 app and book-ui fixture wish 1 = UI-book-dream.
Never builds, installs, clears app data, or touches physical devices. Imports the
existing UI and read-only database snapshot helpers. All generated content and
diagnostic captures stay in ignored artifacts/. The one synthetic Download PNG
and the resulting demo wish records remain available for manual inspection.

Run normally for an evidence-based DocumentsUI selection attempt, or pass
--inspect-picker to stop at the system picker and record its actual UI tree.
An unfamiliar picker is a reported block, never a coordinate guess.
"""
import argparse
import hashlib
import importlib.util
import json
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / 'artifacts'
FIXTURE_NAME = 'tallybook-ui-dream-128x96.png'
DEVICE_FIXTURE = '/sdcard/Download/' + FIXTURE_NAME
TITLE = 'UI-book-dream'


def load_helper(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


UI = load_helper('book_image_ui_helpers', 'book-ui-smoke.py')
FINANCE = load_helper('book_image_finance_helpers', 'finance-ui-smoke.py')
adb, tap, find = UI.adb, UI.tap, UI.find


class PickerNeedsInspection(RuntimeError):
    pass


def png_fixture():
    """Deterministic 128x96 RGB image, made entirely with the Python standard library."""
    width, height = 128, 96
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG scanline filter: none.
        for x in range(width):
            if 42 <= x < 86 and 22 <= y < 74:
                rows.extend((238, 179, 66))
            else:
                rows.extend((25 + x // 2, 90 + y, 70 + ((x + y) % 50)))

    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)

    raw = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(bytes(rows), 9)) + chunk(b'IEND', b''))
    ARTIFACTS.mkdir(exist_ok=True)
    path = ARTIFACTS / FIXTURE_NAME
    assert path.resolve().is_relative_to(ARTIFACTS.resolve())
    path.write_bytes(raw)
    return path, raw


def push_fixture(path, raw):
    # Never overwrite an unrelated file, even on this disposable test AVD.
    try:
        adb('shell', 'test', '-e', DEVICE_FIXTURE)
    except subprocess.CalledProcessError as error:
        assert error.returncode == 1, 'Could not inspect the fixed test destination'
    else:
        assert adb('exec-out', 'cat', DEVICE_FIXTURE, binary=True) == raw, 'Protect an existing non-test Download file'
    adb('shell', 'mkdir', '-p', '/sdcard/Download')
    adb('push', str(path), DEVICE_FIXTURE)
    assert adb('exec-out', 'cat', DEVICE_FIXTURE, binary=True) == raw
    adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE',
        '-d', 'file://' + DEVICE_FIXTURE)


def prefs(name):
    assert re.fullmatch(r'[a-z_]+', name)
    filename = name + '.xml'
    if filename not in adb('shell', 'run-as', UI.PACKAGE, 'ls', 'shared_prefs').split():
        return None
    root = ET.fromstring(adb('exec-out', 'run-as', UI.PACKAGE, 'cat', 'shared_prefs/' + filename))
    result = {}
    for entry in root:
        key = entry.attrib['name']
        if entry.tag in ('int', 'long'):
            value = int(entry.attrib['value'])
        elif entry.tag == 'boolean':
            value = entry.attrib['value'] == 'true'
        elif entry.tag == 'string':
            value = entry.text or ''
        else:
            value = ET.tostring(entry, encoding='unicode')
        result[key] = (entry.tag, value)
    return result


def empty_wish(wish):
    return (wish['title'] == '' and wish['reason'] == '' and not wish['priority']
            and wish['targetMinor'] == 0 and wish['savedMinor'] == 0
            and wish['targetDate'] is None and wish['imageUri'] == '')


def write_tree(tree, stem):
    (ARTIFACTS / (stem + '.xml')).write_bytes(ET.tostring(tree, encoding='utf-8', xml_declaration=True))
    summary = [{key: node.attrib.get(key, '') for key in
                ('package', 'resource-id', 'class', 'text', 'content-desc', 'clickable', 'enabled', 'bounds')}
               for node in tree.iter('node') if node.attrib.get('text') or node.attrib.get('content-desc')]
    (ARTIFACTS / (stem + '.json')).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'tree': stem + '.xml', 'visible_nodes': summary}, ensure_ascii=False), flush=True)


def clickable_match(tree, *, texts=(), descriptions=(), description_prefixes=()):
    """Choose only a visible labeled node with an observed clickable ancestor."""
    parents = {child: parent for parent in tree.iter() for child in parent}
    matches = []
    for node in tree.iter('node'):
        description = node.attrib.get('content-desc', '')
        if (node.attrib.get('text') not in texts and description not in descriptions
                and not any(description.startswith(prefix) for prefix in description_prefixes)):
            continue
        current = node
        while current is not None and current.tag == 'node':
            bounds = re.findall(r'\d+', current.attrib.get('bounds', ''))
            if (current.attrib.get('clickable') == 'true' and current.attrib.get('enabled') != 'false'
                    and len(bounds) == 4):
                x1, y1, x2, y2 = map(int, bounds)
                if x2 > x1 and y2 > y1:
                    if current not in matches:
                        matches.append(current)
                    break
            current = parents.get(current)
    return matches[0] if len(matches) == 1 else None


def tap_observed(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def fixture_tile(tree):
    node = clickable_match(tree, texts=(FIXTURE_NAME,), descriptions=(FIXTURE_NAME,),
                           description_prefixes=(FIXTURE_NAME + ', ',))
    if node is not None:
        return node
    # DocumentsUI's observed AdapterView tiles use onItemClick even though the
    # tile and its ancestors report clickable=false. Only this exact fixture is eligible.
    matches = [n for n in tree.iter('node')
               if n.attrib.get('package') in ('com.android.documentsui', 'com.google.android.documentsui')
               and n.attrib.get('content-desc', '').startswith(FIXTURE_NAME + ', ')
               and n.attrib.get('focusable') == 'true' and n.attrib.get('enabled') != 'false']
    if len(matches) != 1:
        return None
    node = matches[0]
    bounds = re.findall(r'\d+', node.attrib.get('bounds', ''))
    if len(bounds) != 4:
        return None
    x1, y1, x2, y2 = map(int, bounds)
    if x2 <= x1 or y2 <= y1:
        return None
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    for preview in tree.iter('node'):
        if preview.attrib.get('content-desc', '') == 'Preview the file ' + FIXTURE_NAME:
            area = re.findall(r'\d+', preview.attrib.get('bounds', ''))
            if len(area) == 4:
                a, b, c, d = map(int, area)
                if a <= x < c and b <= y < d:
                    return None
    return node


def wait_for_picker():
    """A launched DocumentsUI can take seconds to replace the still-visible form.

    Wait only on that known form, never tap again. Dialogs or foreign surfaces
    require inspection immediately instead of treating them as a slow launch.
    """
    until = time.monotonic() + 30
    while True:
        tree = UI.dump()
        nodes = list(tree.iter('node'))
        packages = {node.attrib.get('package', '') for node in nodes}
        if packages.intersection(('com.android.documentsui', 'com.google.android.documentsui')):
            return tree
        known_form = (UI.PACKAGE in packages
                      and packages.issubset({UI.PACKAGE, 'com.android.systemui', ''})
                      and any(node.attrib.get('content-desc') == '选择钱钱练习页面' for node in nodes)
                      and any(node.attrib.get('text') == '愿望与梦想' for node in nodes))
        dialog = any(node.attrib.get('resource-id', '') in (
            'android:id/alertTitle', 'android:id/parentPanel', 'android:id/button1',
            'android:id/button2', 'android:id/button3') for node in nodes)
        if not known_form or dialog or time.monotonic() >= until:
            write_tree(tree, 'book-image-picker-needs-inspection')
            UI.screenshot('book-image-picker-needs-inspection.png')
            raise PickerNeedsInspection('DocumentsUI did not replace the known dream form within 30 seconds.'
                                        if known_form and not dialog else 'Unexpected surface or dialog while waiting for DocumentsUI.')
        time.sleep(min(0.5, max(0, until - time.monotonic())))


def choose_fixture(inspect_only=False):
    selected_file = opened_drawer = opened_downloads = False
    confirmed_open = False
    selection_deadline = 0
    initial_tree = wait_for_picker()
    step = 0
    while step < 8 or selected_file:
        tree = initial_tree if step == 0 else UI.dump()
        packages = {n.attrib.get('package', '') for n in tree.iter('node')}
        if selected_file and UI.PACKAGE in packages:
            return
        if step == 0:
            write_tree(tree, 'book-image-picker-initial')
        document_picker = any(p in ('com.android.documentsui', 'com.google.android.documentsui') for p in packages)
        if inspect_only or not document_picker:
            write_tree(tree, 'book-image-picker-needs-inspection')
            UI.screenshot('book-image-picker-needs-inspection.png')
            raise PickerNeedsInspection('Inspect the saved picker tree and adapt only observed labels before retrying.')

        file_node = fixture_tile(tree)
        open_node = clickable_match(tree, texts=('Open', 'OPEN', '打开', '选择'), descriptions=('Open', '打开'))
        downloads = clickable_match(tree, texts=('Downloads', 'Download', '下载', '下载内容'))
        drawer = clickable_match(tree, descriptions=('Show roots', '显示根目录', '显示存储设备', '打开导航抽屉', '显示来源', '显示导航抽屉'))
        if selected_file:
            if time.monotonic() >= selection_deadline:
                write_tree(tree, 'book-image-picker-needs-inspection')
                raise PickerNeedsInspection('Selected fixture did not return to the app within 30 seconds.')
            if open_node is not None and not confirmed_open:
                tap_observed(open_node)
                confirmed_open = True
            # Once selected, wait for the result; never navigate or select the file again.
        elif not selected_file and file_node is not None:
            tap_observed(file_node)
            selected_file = True
            selection_deadline = time.monotonic() + 30
        elif not opened_downloads and downloads is not None:
            tap_observed(downloads)
            opened_downloads = True
        elif not opened_drawer and drawer is not None:
            tap_observed(drawer)
            opened_drawer = True
        else:
            write_tree(tree, 'book-image-picker-needs-inspection')
            UI.screenshot('book-image-picker-needs-inspection.png')
            raise PickerNeedsInspection('No unique observed route to the fixed fixture; no guessed tap was performed.')
        time.sleep(0.5)
        step += 1
    tree = UI.dump()
    write_tree(tree, 'book-image-picker-needs-inspection')
    raise PickerNeedsInspection('Picker did not return after the bounded observed-label navigation.')


def preview_present():
    node = find(desc='梦想图片缩略预览，点击查看', scroll=True, timeout=25)
    assert node.attrib.get('class') == 'android.widget.ImageView'
    assert node.attrib.get('clickable') == 'true'
    bounds = list(map(int, re.findall(r'\d+', node.attrib['bounds'])))
    assert bounds[2] > bounds[0] and bounds[3] > bounds[1]
    for _ in range(6):
        if any('梦想图片：' + FIXTURE_NAME in n.attrib.get('text', '') for n in UI.dump().iter('node')):
            return
        UI.swipe()
    raise AssertionError('The image preview must identify the synthetic selected file')


def start_dreams():
    packages = {n.attrib.get('package', '') for n in UI.dump().iter('node')}
    if packages.intersection(('com.android.documentsui', 'com.google.android.documentsui')):
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(0.3)
    UI.start_coach('演示')
    UI.page('愿望与梦想')
    assert find(desc='愿望').attrib.get('text') == TITLE


def record_check(checks, label):
    checks.append(label)
    print('book-image-ui-smoke: ' + label, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inspect-picker', action='store_true', help='Stop at the actual SAF picker and record its tree')
    args = parser.parse_args()
    assert UI.SERIAL == 'emulator-5554' and FINANCE.UI.SERIAL == UI.SERIAL
    assert 'tallybook_api35' in adb('emu', 'avd', 'name'), 'Only the disposable tallybook_api35 AVD is allowed'
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1'
    ARTIFACTS.mkdir(exist_ok=True)
    previous_uri = ''
    previous_result = ARTIFACTS / 'book-image-ui-smoke.json'
    if previous_result.exists():
        try:
            receipt = json.loads(previous_result.read_text(encoding='utf-8'))
            if receipt.get('fixture', {}).get('name') == FIXTURE_NAME and receipt.get('fictional_data') is True:
                previous_uri = receipt.get('image_uri', '')
        except (ValueError, OSError):
            pass
    checks = []
    result = {'passed': False, 'device': UI.SERIAL, 'avd': 'tallybook_api35',
              'fictional_data': True, 'checks': checks, 'live_wechat_tested': False}
    try:
        start_dreams()
        before = UI.state('demo')
        real_before = prefs('money_coach_wechat')
        plans_before = prefs('plans_demo')
        other_prefs = {name: prefs(name) for name in ('plans_wechat', 'monthly_review_wechat', 'monthly_review_demo')}
        finance_before, ledger_before = FINANCE.snapshot()
        assert before['wishes'][0]['title'] == TITLE, 'Protect a non-test first wish'
        existing_uri = before['wishes'][0]['imageUri']
        assert not existing_uri or FIXTURE_NAME in existing_uri or existing_uri == previous_uri, 'Protect a pre-existing non-test image association'
        if existing_uri:
            result['image_uri'] = existing_uri
        empty_slots = [w['slot'] for w in before['wishes'] if empty_wish(w)]
        assert empty_slots, 'A completely empty wish is required; no saved wish will be replaced'
        target_slot = empty_slots[0]
        assert plans_before is not None and all(key in plans_before for key in ('goal_name', 'goal_target', 'goal_saved', 'goal_date')), 'Existing demo legacy goal is required'
        assert all(plans_before[key][0] == expected for key, expected in
                   (('goal_name', 'string'), ('goal_target', 'long'), ('goal_saved', 'long'), ('goal_date', 'string')))
        plan = {key: value[1] for key, value in plans_before.items()}

        path, raw = png_fixture()
        result['fixture'] = {'name': FIXTURE_NAME, 'width': 128, 'height': 96, 'sha256': hashlib.sha256(raw).hexdigest()}
        push_fixture(path, raw)
        record_check(checks, 'fixed_synthetic_png_bytes_verified_on_allowed_avd')
        tap(text='更换梦想图片' if before['wishes'][0]['imageUri'] else '选择梦想图片', scroll=True)
        choose_fixture(args.inspect_picker)
        preview_present()
        UI.screenshot('book-image-selected-preview.png')
        tap(text='保存这个愿望', scroll=True)
        saved = UI.wait_state(lambda s: s['wishes'][0]['imageUri'].startswith('content://'))
        image_uri = saved['wishes'][0]['imageUri']
        result['image_uri'] = image_uri  # Synthetic SAF reference, kept only in ignored artifacts for safe retries.
        expected_wish = dict(before['wishes'][0], imageUri=image_uri)
        assert saved['wishes'][0] == expected_wish
        assert saved['wishes'][1:] == before['wishes'][1:]
        assert {k: v for k, v in saved.items() if k != 'wishes'} == {k: v for k, v in before.items() if k != 'wishes'}
        record_check(checks, 'saf_selected_image_preview_and_uri_saved_without_other_wish_changes')

        start_dreams()  # Explicit force-stop and restart inside the shared helper.
        assert UI.state('demo')['wishes'][0]['imageUri'] == image_uri
        preview_present()
        UI.screenshot('book-image-restarted-preview.png')
        record_check(checks, 'force_stop_restart_retains_uri_permission_and_visible_preview')

        tap(text='复制原存钱目标到一个空愿望', scroll=True)
        find(text='复制原存钱目标？')
        tap(text='确认')
        copied = UI.wait_state(lambda s: not empty_wish(s['wishes'][target_slot]))
        goal_wish = copied['wishes'][target_slot]
        assert goal_wish['title'] == plan['goal_name']
        assert goal_wish['targetMinor'] == plan['goal_target'] and goal_wish['savedMinor'] == plan['goal_saved']
        assert goal_wish['targetDate'] == plan['goal_date'] and not goal_wish['priority'] and goal_wish['imageUri'] == ''
        assert '手动记录' in goal_wish['reason'] and '没有迁移资金' in goal_wish['reason']
        assert all(copied['wishes'][i] == saved['wishes'][i] for i in range(10) if i != target_slot)
        assert {k: v for k, v in copied.items() if k != 'wishes'} == {k: v for k, v in saved.items() if k != 'wishes'}
        assert find(desc='愿望').attrib.get('text') == plan['goal_name']
        UI.screenshot('book-legacy-goal-copied.png')
        record_check(checks, 'legacy_goal_copies_to_first_empty_slot_and_preserves_all_existing_wishes')

        assert prefs('plans_demo') == plans_before, 'Copy must preserve original legacy planning data'
        assert prefs('money_coach_wechat') == real_before, 'Demo image and copy must not change real practice data'
        assert {name: prefs(name) for name in other_prefs} == other_prefs
        finance_after, ledger_after = FINANCE.snapshot()
        assert finance_after == finance_before and ledger_after == ledger_before, 'Image or legacy goal copy must not move or duplicate money'
        record_check(checks, 'original_goal_real_practice_monthly_records_and_both_ledgers_finance_unchanged')
        result.update({'passed': True, 'copied_wish_slot': target_slot,
                       'image_selection_and_restart_preview_tested': True,
                       'large_image_sampling_tested': False, 'image_viewer_open_tested': False})
    except PickerNeedsInspection as error:
        result.update({'blocked': True, 'reason': str(error)})
        print('Picker inspection required; see ignored artifacts/book-image-picker-needs-inspection.xml', flush=True)
    except Exception as error:
        result.update({'error_type': type(error).__name__, 'error': str(error)})
        try:
            write_tree(UI.dump(), 'book-image-failure')
            UI.screenshot('book-image-failure.png')
        except Exception:
            pass
        raise
    finally:
        (ARTIFACTS / 'book-image-ui-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    if not result['passed']:
        sys.exit(2)


if __name__ == '__main__':
    main()
