"""Offline tests only: fictional records, fake ADB and a fake JS bridge."""

import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("query_wechat", Path(__file__).with_name("query-wechat-bills.py"))
query = importlib.util.module_from_spec(spec)
spec.loader.exec_module(query)


def row(identifier="fiction-001", **extra):
    return dict(bill_id=identifier, title="虚构午餐", trans_id="0001234567890123456789",
                timestamp=1760000000, fee=1234, fee_type="CNY", fee_attr="negtive", **extra)


class QueryTests(unittest.TestCase):
    def test_target_allowlist(self):
        self.assertTrue(query.valid_bill_url("https://tenpay.wechatpay.cn/userroll/readtemplate?t=fiction"))
        self.assertTrue(query.valid_bill_url("https://wx.tenpay.com/userroll/readtemplate"))
        for url in ("http://tenpay.wechatpay.cn/userroll/readtemplate",
                    "https://tenpay.wechatpay.cn.evil.invalid/userroll/readtemplate",
                    "https://user@tenpay.wechatpay.cn/userroll/readtemplate",
                    "https://tenpay.wechatpay.cn/userroll/userrolldelete",
                    "https://tenpay.wechatpay.cn:8443/userroll/readtemplate"):
            self.assertFalse(query.valid_bill_url(url))

    def test_emulator_and_invalid_serial_rejected(self):
        for serial in ("emulator-5554", "a;echo secret", "", "-s"):
            with self.assertRaises(query.QueryError):
                query.Adb("fiction-adb", serial)

    def test_forward_only_removes_own_mapping(self):
        class FakeAdb:
            serial = "fiction-phone"
            commands = []

            def run(self, *args, **kwargs):
                self.commands.append(args)
                if args[1] == "tcp:0":
                    return "45000"
                if args[1] == "--list":
                    return "fiction-phone tcp:1294 localabstract:existing\nfiction-phone tcp:45000 localabstract:fixture"
                return ""

        adb = FakeAdb()
        with self.assertRaises(ValueError):
            with query.Forward(adb, "fixture") as port:
                self.assertEqual(port, 45000)
                raise ValueError("fictional failure")
        self.assertIn(("forward", "--remove", "tcp:45000"), adb.commands)
        self.assertNotIn(("forward", "--remove", "tcp:1294"), adb.commands)
        self.assertNotIn(("forward", "--remove-all"), adb.commands)

    def test_websocket_forces_localhost(self):
        target = {"webSocketDebuggerUrl":"ws://untrusted.invalid/devtools/page/FICTION-ID"}
        self.assertEqual(query.ws_address(45678, target), "ws://127.0.0.1:45678/devtools/page/FICTION-ID")
        with self.assertRaises(query.QueryError):
            query.ws_address(45678, {"webSocketDebuggerUrl":"ws://localhost/devtools/page/F?token=secret"})

    def test_money_keeps_neutral_and_unknown_blank(self):
        self.assertEqual(query.signed_fee(row()), -1234)
        for attr in ("neutral", "negative", "unexpected"):
            value = row()
            value["fee_attr"] = attr
            self.assertEqual(query.signed_fee(value), "")
        value = row()
        value["fee_attr"] = "positive"
        self.assertEqual(query.signed_fee(value), 1234)
        value["fee_type"] = "USD"
        self.assertEqual(query.signed_fee(value), "")
        value["fee_type"], value["fee"] = "CNY", -1234
        self.assertEqual(query.signed_fee(value), "")

    def test_csv_does_not_execute_formula(self):
        for text in ("=1+1", "+CMD()", "-CMD()", "@SUM(1)", "\t=1+1", "  =1+1"):
            self.assertTrue(query.csv_cell(text).startswith("'"))
        self.assertEqual(query.csv_cell(-1234), -1234)
        self.assertEqual(query.csv_cell("虚构午餐"), "虚构午餐")

    def test_credentials_and_duplicates_rejected_before_export(self):
        for records in ([dict(row(), exportkey="fiction-secret")], [row(), row()]):
            with self.assertRaises(query.QueryError):
                query.validate_result({"records":records, "pages":[]})

    def test_same_bill_different_transaction_is_preserved(self):
        second = row()
        second["trans_id"] = "fiction-different-trade"
        rows, _ = query.validate_result({"records":[row(), second], "pages":[]})
        self.assertEqual(len(rows), 2)

    def test_exports_preserve_types_and_prior_files(self):
        with tempfile.TemporaryDirectory() as directory:
            location = Path(directory)
            pages = [{"ret_code":0, "count":1, "added":1}]
            query.export_records(location, [row()], pages, "page_limit")
            query.export_records(location, [row()], pages, "page_limit")
            files = list(location.glob("*.json"))
            self.assertEqual(len(files), 2)
            saved = json.loads(files[0].read_text(encoding="utf-8"))
            self.assertEqual(saved["records"][0], row())
            self.assertEqual(len(list(location.glob("*.csv"))), 2)


@unittest.skipUnless(shutil.which("node"), "Node.js is needed only for offline bridge fixtures")
class BridgeFixtureTests(unittest.TestCase):
    def run_bridge(self, replies, pages=2):
        prefix = """
global.location = {href:'https://tenpay.wechatpay.cn/userroll/readtemplate?exportkey=FICTION_SECRET'};
global.window = {svrData:{extra_params:JSON.stringify({csrf_token:'FICTION_CSRF'})}};
const replies = REPLIES;
const requests = [];
global.WeixinJSBridge = window.WeixinJSBridge = {invoke(name, args, callback) {
  if (name !== 'nativeWXPayCgiTunnel' || args.cmd !== 'userrolllist' || args.cgi_id !== '5004')
    throw new Error('Unexpected native operation');
  const data = JSON.parse(args.reqbuf);
  if (data.exportkey !== 'FICTION_SECRET' || data.csrf_token !== 'FICTION_CSRF' ||
      data.count !== 20 || data.sort_type !== 1 || data.classify_type !== 0)
    throw new Error('Unexpected request');
  requests.push(data);
  callback({err_msg:'nativeWXPayCgiTunnel:ok', respbuf:JSON.stringify(replies.shift())});
}};
""".replace("REPLIES", json.dumps(replies, ensure_ascii=False))
        tail = """.then(result => {
  if (requests.length > 1 && requests[1].start_time !== 1760000000)
    throw new Error('Missing cursor timestamp');
  const output = JSON.stringify(result);
  if (output.includes('FICTION_SECRET') || output.includes('FICTION_CSRF'))
    throw new Error('Credential leak');
  process.stdout.write(output);
}).catch(() => process.exit(2));"""
        completed = subprocess.run([shutil.which("node"), "-"],
                                   input=prefix + query.query_expression(pages) + tail,
                                   capture_output=True, text=True, encoding="utf-8", timeout=3)
        self.assertEqual(completed.returncode, 0)
        return json.loads(completed.stdout)

    def response(self, rows, **extra):
        return dict(ret_code=0, record=rows, is_over=0, last_bill_id="fiction-cursor",
                    last_bill_type=1, last_trans_id="fiction-trade", last_create_time=1760000000, **extra)

    def test_two_pages_keep_cursor_in_page_and_dedupe(self):
        first = row(icon_url="https://example.invalid/icon.png?token=FICTION_SECRET#fragment")
        result = self.run_bridge([self.response([first]), self.response([row("fiction-002")])])
        self.assertEqual(len(result["records"]), 2)
        self.assertEqual(result["records"][0]["icon_url"], "https://example.invalid/icon.png")
        self.assertEqual(result["stop"], "page_limit")
        self.assertEqual(result["pages"][1]["added"], 1)

    def test_repeated_page_stops_without_duplicate(self):
        result = self.run_bridge([self.response([row()]), self.response([row()])], pages=3)
        self.assertEqual(len(result["records"]), 1)
        self.assertEqual(result["stop"], "no_new_records")

    def test_same_bill_different_transaction_is_not_merged(self):
        second = row()
        second["trans_id"] = "fiction-different-trade"
        result = self.run_bridge([self.response([row()]), self.response([second])])
        self.assertEqual(len(result["records"]), 2)

    def test_server_error_returns_only_numeric_code(self):
        result = self.run_bridge([{"ret_code":268511753, "ret_msg":"FICTION_SECRET"}])
        self.assertEqual(result["records"], [])
        self.assertEqual(result["stop"], "server_error")
        self.assertEqual(result["pages"][0]["ret_code"], 268511753)

    def test_final_page_does_not_require_unused_next_cursor(self):
        result = self.run_bridge([{"ret_code":0, "record":[row()]}], pages=1)
        self.assertEqual(result["stop"], "page_limit")

    def test_unrecognized_fields_and_credentials_never_exported(self):
        result = self.run_bridge([self.response([dict(row(), csrf_token="FICTION_CSRF",
                                                      nested={"exportkey":"FICTION_SECRET"})])], pages=1)
        self.assertEqual(set(result["records"][0]), set(row()))


if __name__ == "__main__":
    unittest.main()
