#!/usr/bin/env python3
"""Read-only, in-session WeChat bill query through an explicitly selected phone.

See docs/WECHAT_API_RESEARCH.md. This is a developer experiment, not a public API.
Only bill records leave the page context; session credentials never leave it.
"""

import argparse
import csv
import ctypes
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from contextlib import ExitStack
from datetime import datetime, timezone
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
HOSTS = {"tenpay.wechatpay.cn", "wx.tenpay.com"}
FIELDS = (
    "bill_id", "trans_id", "title", "timestamp", "fee", "fee_type", "fee_attr",
    "current_state", "current_state_type", "bill_type", "icon_url", "out_trade_no",
)
SETUP_HINT = (
    "未找到可用的微信账单调试页。请在指定手机的微信内打开 "
    "https://debugxweb.qq.com/?inspector=true，按微信提示允许调试，"
    "再打开自己的账单列表并保持该页面。"
)


class QueryError(Exception):
    """Only fixed, non-sensitive messages may be used here."""


def valid_bill_url(value):
    try:
        url = urlsplit(value)
        return (url.scheme == "https" and url.hostname in HOSTS
                and url.port in (None, 443) and not url.username and not url.password
                and url.path == "/userroll/readtemplate")
    except (TypeError, ValueError):
        return False


def find_adb():
    candidates = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]) / "platform-tools" / "adb.exe")
            candidates.append(Path(os.environ[variable]) / "platform-tools" / "adb")
    candidates.extend((ROOT / ".tools/android-sdk/platform-tools/adb.exe",
                       ROOT / ".tools/android-sdk/platform-tools/adb"))
    found = shutil.which("adb")
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise QueryError("找不到 ADB。请先按 docs/DEVELOPMENT.md 准备环境并加载 .tools/env.ps1。")


class Adb:
    def __init__(self, executable, serial):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}", serial):
            raise QueryError("请用 --serial 显式指定有效的物理手机序列号。")
        if serial.lower().startswith("emulator-"):
            raise QueryError("此脚本拒绝模拟器，请指定实验手机。")
        self.executable, self.serial = executable, serial

    def run(self, *args, optional=False):
        try:
            result = subprocess.run(
                [self.executable, "-s", self.serial, *args], capture_output=True,
                timeout=8, encoding="utf-8", errors="replace",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except (OSError, subprocess.TimeoutExpired):
            if optional:
                return ""
            raise QueryError("ADB 操作未完成，请检查指定手机的 USB 连接与调试授权。") from None
        if result.returncode:
            if optional:
                return ""
            raise QueryError("ADB 操作失败，请检查指定手机的 USB 连接与调试授权。")
        return result.stdout.strip()

    def check_phone(self):
        if self.run("get-state") != "device":
            raise QueryError("指定手机尚未就绪，请解锁并接受 USB 调试授权。")
        if self.run("shell", "getprop", "ro.kernel.qemu") == "1":
            raise QueryError("此脚本拒绝模拟器，请指定实验手机。")
        characteristics = self.run("shell", "getprop", "ro.build.characteristics")
        if re.search(r"\b(tablet|tv|watch|automotive)\b", characteristics, re.I):
            raise QueryError("指定设备报告为平板或其他非手机设备，已停止。")

    def sockets(self):
        processes = self.run("shell", "ps", "-A", "-o", "PID,NAME")
        pids = set()
        for line in processes.splitlines():
            pieces = line.split()
            if (len(pieces) == 2 and pieces[0].isdigit()
                    and re.fullmatch(r"com\.tencent\.mm(?::[A-Za-z0-9_.-]+)?", pieces[1])):
                pids.add(pieces[0])
        unix = self.run("shell", "cat", "/proc/net/unix", optional=True)
        sockets = set()
        for line in unix.splitlines():
            name = line.split()[-1] if line.split() else ""
            match = re.fullmatch(r"@((?:webview|xweb)_devtools_remote_(\d+))", name)
            if match and match.group(2) in pids:
                sockets.add(match.group(1))
        return sorted(sockets)[:8]


class Forward:
    def __init__(self, adb, remote):
        self.adb, self.remote, self.port = adb, "localabstract:" + remote, None

    def __enter__(self):
        port = self.adb.run("forward", "tcp:0", self.remote)
        if not port.isdecimal() or not 1 <= int(port) <= 65535:
            raise QueryError("ADB 未返回临时转发端口，已停止。")
        self.port = int(port)
        return self.port

    def __exit__(self, *ignored):
        # Never remove all forwards or another program's existing mapping.
        try:
            current = self.adb.run("forward", "--list")
            own = [self.adb.serial, "tcp:" + str(self.port), self.remote]
            if any(line.split() == own for line in current.splitlines()):
                self.adb.run("forward", "--remove", own[1])
        except QueryError:
            print("临时转发清理未能确认，请检查该手机的 ADB 转发；其他转发未处理。", file=sys.stderr)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def bill_targets(port):
    # ADB/CDP traffic stays on loopback, including on hosts with a configured proxy.
    try:
        opener = build_opener(ProxyHandler({}), NoRedirect())
        with opener.open("http://127.0.0.1:%d/json/list" % port, timeout=3) as response:
            content = response.read(1024 * 1024 + 1)
        if len(content) > 1024 * 1024:
            return []
        targets = json.loads(content)
        if not isinstance(targets, list):
            return []
        return [target for target in targets if isinstance(target, dict)
                and target.get("type") == "page" and valid_bill_url(target.get("url", ""))]
    except Exception:
        # HTTP errors and target URLs can contain credentials; never print them.
        return []


def ws_address(port, target):
    try:
        url = urlsplit(target.get("webSocketDebuggerUrl", ""))
        if (url.scheme != "ws" or url.query or url.fragment
                or not re.fullmatch(r"/devtools/page/[A-Za-z0-9_-]+", url.path)):
            raise ValueError()
        return "ws://127.0.0.1:%d%s" % (port, url.path)
    except (TypeError, ValueError):
        raise QueryError("账单页未提供受支持的 CDP 连接地址。") from None


# No Vue store access and no page navigation. Credentials and pagination cursors
# stay in this closure. The only invoked native command is the read-only list.
QUERY_JS = r"""
(async function () {
  const pages = __PAGES__;
  const allowedHosts = ['tenpay.wechatpay.cn', 'wx.tenpay.com'];
  const fields = __FIELDS__;
  const cursorNames = ['last_bill_id','last_bill_type','last_trans_id','last_create_time'];
  const startUrl = location.href;
  const pageUrl = new URL(startUrl);
  const result = {records: [], pages: [], stop: 'page_limit'};
  const fail = (code) => { result.stop = code; return result; };
  if (pageUrl.protocol !== 'https:' || !allowedHosts.includes(pageUrl.hostname) ||
      pageUrl.pathname !== '/userroll/readtemplate') return fail('wrong_page');
  if (!window.WeixinJSBridge || typeof WeixinJSBridge.invoke !== 'function')
    return fail('bridge_unavailable');
  const exportkey = pageUrl.searchParams.get('exportkey');
  if (!exportkey) return fail('session_missing');
  let extra;
  try { extra = JSON.parse(window.svrData && window.svrData.extra_params || '{}'); }
  catch (_) { return fail('session_invalid'); }
  if (!extra || Array.isArray(extra) || typeof extra !== 'object')
    return fail('session_invalid');
  const seen = new Set(), cursors = new Set();
  let next = {};
  for (let page = 0; page < pages; page++) {
    if (location.href !== startUrl) return fail('page_changed');
    const data = Object.assign({}, extra, {exportkey}, next,
                               {count: 20, sort_type: 1, classify_type: 0});
    const reply = await new Promise((resolve) => {
      let done = false;
      const finish = (value) => { if (!done) { done = true; clearTimeout(timer); resolve(value); } };
      const timer = setTimeout(() => finish({failure:'bridge_timeout'}), 6500);
      try {
        WeixinJSBridge.invoke('nativeWXPayCgiTunnel', {
          cmd:'userrolllist', cgi_id:'5004',
          cgi_url:'/cgi-bin/mmpay-bin/tunnel_userrollsecuretunnel',
          timeout:5000, reqbuf:JSON.stringify(data)
        }, (response) => {
          try {
            if (!response || response.err_msg !== 'nativeWXPayCgiTunnel:ok')
              return finish({failure:'bridge_failed'});
            const parsed = JSON.parse(response.respbuf);
            if (!parsed || typeof parsed !== 'object') return finish({failure:'invalid_response'});
            finish({data:parsed});
          } catch (_) { finish({failure:'invalid_response'}); }
        });
      } catch (_) { finish({failure:'bridge_failed'}); }
    });
    if (location.href !== startUrl) return fail('page_changed');
    if (reply.failure) return fail(reply.failure);
    const value = reply.data;
    const code = Number(value.ret_code);
    if (!Number.isSafeInteger(code)) return fail('invalid_response');
    if (code !== 0) {
      result.pages.push({ret_code:code, count:0, added:0});
      return fail('server_error');
    }
    const rows = value.record;
    if (!Array.isArray(rows) || rows.length > 20) return fail('schema_changed');
    let added = 0;
    for (const row of rows) {
      if (!row || typeof row.bill_id !== 'string' || !row.bill_id || row.bill_id.length > 512 ||
          typeof row.trans_id !== 'string' || row.trans_id.length > 512 ||
          !Number.isSafeInteger(row.timestamp) || row.timestamp <= 0)
        return fail('schema_changed');
      const identity = JSON.stringify([row.bill_id, row.trans_id, row.timestamp]);
      if (seen.has(identity)) continue;
      const clean = {};
      for (const field of fields) {
        if (!Object.prototype.hasOwnProperty.call(row, field)) continue;
        const item = row[field];
        if (item !== null && !['string','number','boolean'].includes(typeof item))
          return fail('schema_changed');
        if (typeof item === 'number' && !Number.isSafeInteger(item)) return fail('schema_changed');
        if (typeof item === 'string' && item.length > 16384) return fail('schema_changed');
        // Icons may carry unrelated URL parameters. Export neither URL queries nor fragments.
        if (field === 'icon_url' && typeof item === 'string') {
          try { const icon = new URL(item); clean[field] = icon.origin + icon.pathname; }
          catch (_) { clean[field] = ''; }
        } else clean[field] = item;
      }
      seen.add(identity); result.records.push(clean); added++;
    }
    result.pages.push({ret_code:code, count:rows.length, added});
    if (!rows.length || !added) return fail('no_new_records');
    if (value.is_over === 1 || value.is_over === '1' || value.is_over === true)
      return fail('complete');
    if (page + 1 === pages) return result;
    next = {};
    for (const key of cursorNames) {
      const item = value[key];
      if (item === undefined || item === null || !['string','number'].includes(typeof item))
        return fail('cursor_missing');
      next[key] = item;
    }
    next.start_time = rows[rows.length - 1].timestamp;
    if (!Number.isSafeInteger(next.start_time) || next.start_time <= 0)
      return fail('cursor_missing');
    const cursor = JSON.stringify(next);
    if (cursors.has(cursor)) return fail('repeated_cursor');
    cursors.add(cursor);
  }
  return result;
})()
"""


def query_expression(pages):
    return QUERY_JS.replace("__PAGES__", str(pages)).replace("__FIELDS__", json.dumps(FIELDS))


def query_cdp(address, pages):
    try:
        import websocket
    except ImportError:
        raise QueryError("缺少依赖，请运行：python -m pip install websocket-client==1.8.0") from None
    connection = None
    try:
        websocket.enableTrace(False)
        connection = websocket.create_connection(
            address, timeout=8, suppress_origin=True,
            http_no_proxy=["127.0.0.1", "localhost"],
        )
        connection.send(json.dumps({"id":1, "method":"Runtime.evaluate", "params":{
            "expression":query_expression(pages), "awaitPromise":True,
            "returnByValue":True, "silent":True,
        }}))
        deadline = time.monotonic() + pages * 7 + 5
        while time.monotonic() < deadline:
            connection.settimeout(min(8, max(0.1, deadline - time.monotonic())))
            try:
                raw = connection.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not raw or len(raw) > 8 * 1024 * 1024:
                break
            message = json.loads(raw)
            if message.get("id") != 1:
                continue  # Do not log console events, request URLs or exception text.
            result = message.get("result", {})
            if "error" in message or "exceptionDetails" in result:
                break
            value = result.get("result", {}).get("value")
            if isinstance(value, dict):
                return value
            break
    except Exception:
        pass
    finally:
        if connection is not None:
            try:
                connection.close(timeout=1)
            except Exception:
                pass
    raise QueryError("账单页查询未完成。请保持原账单页、检查连接后重试；异常正文已隐藏。")


def validate_result(value):
    rows, pages = value.get("records"), value.get("pages")
    if not isinstance(rows, list) or len(rows) > 200 or not isinstance(pages, list) or len(pages) > 10:
        raise QueryError("账单响应结构已变化，未写入文件。")
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or not set(row).issubset(FIELDS):
            raise QueryError("账单响应含未识别字段，未写入文件。")
        identifier = row.get("bill_id")
        trade_id, occurred_at = row.get("trans_id"), row.get("timestamp")
        if (not isinstance(identifier, str) or not identifier or len(identifier) > 512
                or not isinstance(trade_id, str) or len(trade_id) > 512
                or type(occurred_at) is not int or not 0 < occurred_at <= 9007199254740991):
            raise QueryError("账单标识无效或重复，未写入文件。")
        identity = (identifier, trade_id, occurred_at)
        if identity in seen:
            raise QueryError("账单标识无效或重复，未写入文件。")
        seen.add(identity)
        for item in row.values():
            if item is not None and (type(item) not in (str, int, float, bool)
                                     or isinstance(item, str) and len(item) > 16384):
                raise QueryError("账单响应含未识别字段，未写入文件。")
    for page in pages:
        if not isinstance(page, dict) or set(page) != {"ret_code", "count", "added"}:
            raise QueryError("分页响应结构已变化，未写入文件。")
        if any(type(item) is not int for item in page.values()):
            raise QueryError("分页响应结构已变化，未写入文件。")
        if not 0 <= page["added"] <= page["count"] <= 20:
            raise QueryError("分页响应数量异常，未写入文件。")
    return rows, pages


def csv_cell(value):
    if value is None:
        return ""
    # Avoid spreadsheet formula execution. JSON is the canonical original-value export.
    if isinstance(value, str) and (value.startswith(("=", "+", "-", "@", "\t", "\r", "\n"))
                                   or value.lstrip().startswith(("=", "+", "-", "@"))):
        return "'" + value
    return value


def signed_fee(row):
    fee = row.get("fee")
    if type(fee) is not int or fee < 0 or row.get("fee_type") != "CNY":
        return ""
    if row.get("fee_attr") == "positive":
        return fee
    if row.get("fee_attr") == "negtive":  # This spelling is used by WeChat.
        return -fee
    return ""


def local_output(value):
    path = Path(value).expanduser().resolve()
    if str(path).startswith(("\\\\", "//")):
        raise QueryError("输出目录必须是本机目录，不能使用网络共享。")
    if os.name == "nt" and ctypes.windll.kernel32.GetDriveTypeW(str(path.anchor)) == 4:
        raise QueryError("输出目录必须是本机目录，不能使用映射网络磁盘。")
    return path


def export_records(directory, rows, pages, stop):
    directory.mkdir(parents=True, exist_ok=True)
    stem = "wechat-bills-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid4().hex[:8]
    data = {"format":"wechat-bill-query-v1", "record_count":len(rows), "pages":pages,
            "stop":stop, "records":rows}
    encoded = json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=[*FIELDS, "signed_fee_minor"])
    writer.writeheader()
    for row in rows:
        writer.writerow({**{key:csv_cell(row.get(key)) for key in FIELDS},
                         "signed_fee_minor":signed_fee(row)})
    for suffix, content, encoding in ((".json", encoded, "utf-8"),
                                       (".csv", buffer.getvalue(), "utf-8-sig")):
        # Unique names + exclusive creation preserve previous exports.
        with open(directory / (stem + suffix), "x", encoding=encoding, newline="") as output:
            output.write(content)


def main():
    parser = argparse.ArgumentParser(description="通过指定手机中已打开的微信账单页查询；不安装应用，不修改账单。")
    parser.add_argument("--serial", required=True, help="明确选择的物理手机 ADB 序列号")
    parser.add_argument("--pages", type=int, default=2, choices=range(1, 11), metavar="1..10",
                        help="最多查询页数，每页 20 条，默认 2 页")
    parser.add_argument("--output", default=str(ROOT / ".tools/wechat-query"),
                        help="本机 JSON/CSV 输出目录；默认 .tools/wechat-query（已被 Git 忽略）")
    args = parser.parse_args()
    output = local_output(args.output)
    adb = Adb(find_adb(), args.serial)
    adb.check_phone()
    with ExitStack() as forwards:
        available = []
        for remote in adb.sockets():
            port = forwards.enter_context(Forward(adb, remote))
            available.extend((port, target) for target in bill_targets(port))
        if not available:
            raise QueryError(SETUP_HINT)
        if len(available) != 1:
            raise QueryError("发现多个微信账单调试页。请只保留一个账单列表，再重试。")
        port, target = available[0]
        value = query_cdp(ws_address(port, target), args.pages)
        rows, pages = validate_result(value)
        for index, page in enumerate(pages, 1):
            print("page=%d ret_code=%d count=%d added=%d" %
                  (index, page["ret_code"], page["count"], page["added"]))
        safe_stops = {"page_limit", "complete", "no_new_records", "repeated_cursor"}
        stop = value.get("stop")
        if stop not in safe_stops:
            # On partial failure, do not silently turn an incomplete run into a successful export.
            raise QueryError("查询未正常完成，未导出文件。请保持账单页并重新打开后重试；不输出错误正文。")
        export_records(output, rows, pages, stop)
        print("已在所选本机目录保存 JSON 和 CSV；总记录数=%d。未修改微信页面列表。" % len(rows))
    print("结束调试后，请在微信内打开 https://debugxweb.qq.com/?inspector=false。")
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        sys.exit(main())
    except QueryError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("已停止查询；结束调试后请在微信内关闭 inspector。", file=sys.stderr)
        sys.exit(130)
    except Exception:
        # Never allow traceback, request contents or OS error paths into normal output.
        print("操作未完成；请检查本机输出目录、依赖和连接。详细异常已隐藏以保护会话与账单。", file=sys.stderr)
        sys.exit(1)
