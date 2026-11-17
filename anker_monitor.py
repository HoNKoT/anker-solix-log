#!/usr/bin/env python3
"""Anker SOLIX モニター (バックエンド)

登録した機器を Bluetooth (SolixBLE) で読み、ローカルHTTPで配る。履歴は SQLite に残す。

  http://localhost:19877/              ダッシュボード (dashboard.html)
  GET  /status                         登録機器の現在値とポート別の内訳 (メニューバーアプリが2秒おきに読む)
  GET  /api/history?dev=&from=&to=     履歴 (合計: 残量・入力・出力)。旧名 /history?dev=… も可
  GET  /history, /devices              履歴ページ・機器ページ (同じ dashboard.html。パスでページを切り替える)
  GET  /ports?dev=&from=&to=           履歴 (ポート別の内訳と期間の集計)
  GET  /summary?dev=&from=&to=         期間の集計 (電力量Wh・最大値など)
  GET  /api/daily?from=&to=&dev=       日ごとの電力量 Wh (入力・出力・ソーラー・AC)
  GET/POST /api/settings               設定 (電気代の単価 price_per_kwh。既定 39.5 円/kWh)
  GET  /api/scan                       近くのAnker機器を探す (機器を探して登録する)
  POST /api/devices                    機器を登録
  POST /api/devices/<id>/rename        名前を変える
  DELETE /api/devices/<id>             登録を外す (履歴は残る)

BLE にペアリング操作は要りません。「登録」は、探して見つけた機器をこのアプリの一覧に加えることです。

履歴の記録ルール: 入力が 0W のときは入力を記録しない (グラフにも描かない)。
ポート別は 0W より大きいものだけ記録する (記録がない = 0W)。

機器を増やすときは Source を継承したクラスを足します (C2000 Gen 2 のクラウド版など)。

  python anker_monitor.py            # 本番
  python anker_monitor.py --demo     # 機器なしでダミー機器・ダミー履歴を出す (画面確認用)
  python anker_monitor.py --host 0.0.0.0   # 他のMacからも見る (LAN内に公開される点に注意)
"""

import argparse
import collections
import asyncio
import hashlib
import hmac
import itertools
import urllib.request
import json
import logging
import math
import os
import random
import signal
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlencode, urlparse

LOG = logging.getLogger("anker-monitor")

DATA_DIR = Path(os.environ.get("ANKER_MONITOR_HOME", Path.home() / ".anker-monitor"))
HTML_FILE = Path(__file__).resolve().with_name("dashboard.html")
BASE_TICK = 10  # 秒ごとに現在値を拾う (これを平均して、履歴には SAMPLE_INTERVAL ごとに1行)
SHARE_MIN_W = 5  # 電力共有とみなす最小の W
SHARE_ABS_W = 10  # 出力と入力の差がこれ以下 (または SHARE_REL 以下) なら「ほぼ同じ量」
SHARE_REL = 0.25  # (変換ロスぶんの差を許す)
RAW_KEEP_DAYS = 3  # 直近この日数ぶんは細かい (SAMPLE_INTERVAL) まま残し、それより古い日は THIN_INTERVAL 秒に間引く
THIN_INTERVAL = 30
SAMPLE_INTERVAL = 10  # 履歴へ書く間隔 (秒)。設定 (sample_interval) で 10 / 30 / 60 に変えられる
GAP_CAP = 100  # 行と行の間がこれ以上空いたら、欠測として電力量に足さない (秒)
CLOUD_FILE = DATA_DIR / "cloud.json"
CLOUD_POLL = 2.0       # 秒: MQTT のキャッシュを読む間隔
CLOUD_STALE = 90       # 秒: これだけ更新が来なければクラウド値は古いとみなす
CLOUD_TRIGGER_EVERY = 240   # 秒: 機器に「リアルタイム送信して」と頼み直す間隔
RECENT_KEEP = 120  # 直近に届いた生の値を何件取っておくか (コピー用)
RAW_LOG_EVERY = 300  # 秒: クラウドの生の値をログに出す間隔 (最初の1回は必ず出す)
CLOUD_STATUS_EVERY = 5  # 秒: 「いまの状態を送って」(status_request) を出す間隔。C300 DC などはこれが無いと値が来ない
CLOUD_TRIGGER_TIMEOUT = 300  # 秒: 1回の依頼で機器が送り続ける長さ

# ───────────────────────── 機種 ─────────────────────────
# icon: メニューバーに描く形 (c300dc / c2000g2 は実物に寄せた専用形。compact=小型 / box=中型 / large=大型 は汎用)
# cls : SolixBLE のクラス名。experimental は「ライブラリ未対応なので近い機種のクラスで試す」もの。
MODELS: dict[str, dict] = {
    "c300dc": {"label": "C300 DC", "cls": "C300DC", "icon": "c300dc"},
    "c300": {"label": "C300(X)", "cls": "C300", "icon": "compact"},
    "c800": {"label": "C800(X)", "cls": "C800", "icon": "box"},
    "c1000": {"label": "C1000(X)", "cls": "C1000", "icon": "box"},
    "c1000g2": {"label": "C1000 Gen 2", "cls": "C1000G2", "icon": "box"},
    "c2000g2": {"label": "C2000 Gen 2", "cls": "C1000G2", "icon": "c2000g2", "experimental": True},
    "f2000": {"label": "F2000 (767)", "cls": "F2000", "icon": "large"},
    "f2600": {"label": "F2600", "cls": "F2600", "icon": "large"},
    "f3800": {"label": "F3800", "cls": "F3800", "icon": "large"},
}

# ポート: SolixBLE の属性名 → (キー, 表示名, 方向)。機種にない属性は自動で飛ばす。
PORT_PROPS = [
    ("solar_power_in", "solar", "ソーラー", "in"),
    ("ac_power_in", "ac_in", "AC入力", "in"),
    ("ac_power_out", "ac_out", "AC出力", "out"),
    ("dc_power_out", "dc_out", "DC出力 (シガー)", "out"),
    ("dc_1_power_out", "dc1_out", "DC出力1", "out"),
    ("dc_2_power_out", "dc2_out", "DC出力2", "out"),
    ("usb_c1_power", "usb_c1", "USB-C1", "out"),
    ("usb_c2_power", "usb_c2", "USB-C2", "out"),
    ("usb_c3_power", "usb_c3", "USB-C3", "out"),
    ("usb_c4_power", "usb_c4", "USB-C4", "out"),
    ("usb_a1_power", "usb_a1", "USB-A1", "out"),
    ("usb_a2_power", "usb_a2", "USB-A2", "out"),
]
PORT_INFO = {key: (label, direction) for _attr, key, label, direction in PORT_PROPS}
# 双方向の USB ポートが入力側になっているぶん (ポート状態 2 = 入力)。usb_ で始めないのは、出力側の USB 合計 (usb_*) に混ぜないため
PORT_INFO["in_usb"] = ("USB入力", "in")
# 機器どうしの給電 (find_sharing で対応づいたぶん)。入力・出力の合計 (pin / pout) からは引き、履歴ではこの列に記録する
PORT_INFO["xfer_in"] = ("Transfer (受け取り)", "in")
PORT_INFO["xfer_out"] = ("Transfer (送り出し)", "out")
# 送り出した W − 相手が受け取った W。入力にも出力にも属さないので dir は "loss" (グラフの入力 / 出力の積み上げに入れない)
PORT_INFO["xfer_loss"] = ("Transfer ロス", "loss")
PORT_INFO["other_in"] = ("入力 その他", "in")
PORT_INFO["other_out"] = ("出力 その他", "out")


def guess_model(name: str | None) -> str | None:
    """Bluetooth の名前から機種を推測する。分からなければ None。"""
    n = (name or "").lower().replace("-", " ")
    if "c2000" in n:
        return "c2000g2"
    if "c1000" in n:
        return "c1000g2" if ("gen 2" in n or "gen2" in n or "g2" in n) else "c1000"
    if "c800" in n:
        return "c800"
    if "c300" in n:
        return "c300dc" if (" dc" in n or n.endswith("dc")) else "c300"
    if "f3800" in n:
        return "f3800"
    if "f2600" in n:
        return "f2600"
    if "f2000" in n or "767" in n:
        return "f2000"
    return None


PN_MODELS = {"A1783": "c2000g2"}  # クラウド上の型番 → 機種 (分かっているものだけ)


MODEL_WH = {"c300dc": 288, "c300": 288, "c800": 768, "c1000": 1056, "c1000g2": 1024, "c2000g2": 2048,
            "f2000": 2048, "f2600": 2560, "f3800": 3840}  # 電池容量 Wh (ロスの推定に使う)
LAN_FLAG = DATA_DIR / "lan"  # このファイルがあれば、同じネットワークのスマホなどからも見られる (amon lan on)
SETTINGS_FILE = DATA_DIR / "settings.json"
TOKEN_FILE = DATA_DIR / "api_token"  # あれば、このMac以外からの読み取りにこのトークンを求める (amon token new)
TOKEN_COOKIE = "amon_token"
PAGE_PATHS = ("/", "/index.html", "/history", "/devices")


def api_token() -> str | None:
    # 毎回読むのは、amon token new / off をバックエンドの再起動なしで効かせるため
    try:
        t = TOKEN_FILE.read_text().strip()
    except OSError:
        return None
    return t or None
DEFAULT_SETTINGS = {"price_per_kwh": 39.5, "sample_interval": 10}


def load_settings() -> dict:
    try:
        return {**DEFAULT_SETTINGS, **json.loads(SETTINGS_FILE.read_text())}
    except Exception:
        return dict(DEFAULT_SETTINGS)


def save_settings(new: dict) -> dict:
    cur = load_settings()
    if "price_per_kwh" in new:
        v = float(new["price_per_kwh"])
        if not (0 <= v <= 10000):
            raise ValueError("電気代の単価が範囲外です")
        cur["price_per_kwh"] = round(v, 2)
    if "location" in new:  # 天気を取る場所。None で取らない
        loc = new["location"]
        if loc:
            lat, lon = float(loc["lat"]), float(loc["lon"])
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                raise ValueError("緯度・経度が範囲外です")
            loc = {"name": str(loc.get("name") or "")[:80], "lat": round(lat, 4), "lon": round(lon, 4)}
        cur["location"] = loc or None
    if "sample_interval" in new:
        v = int(new["sample_interval"])
        if v not in (10, 30, 60):
            raise ValueError("履歴の間隔は 10 / 30 / 60 秒から選んでください")
        cur["sample_interval"] = v
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    SETTINGS_FILE.write_text(json.dumps(cur))
    apply_settings(cur)
    if "location" in new:
        WEATHER_WAKE.set()
    return cur


# ───────────────────────── 天気 (Open-Meteo) ─────────────────────────
# キー不要・無料の Open-Meteo から、設定した場所の日ごとの天気を取る。ソーラーの発電量と見比べるため
WEATHER_DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,sunshine_duration,shortwave_radiation_sum"
WEATHER_EVERY = 3 * 3600
WEATHER_WAKE = threading.Event()  # 場所を変えたら、待たずに取り直す


def _get_json(url: str, params: dict) -> dict:
    req = urllib.request.Request(url + "?" + urlencode(params), headers={"User-Agent": "anker-monitor"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def geocode(q: str) -> list[dict]:
    # 日本語の地名は「東京」では 0 件、「東京都」なら出るので、0 件のときは市・都・府・県を付けて探し直す
    for name in (q, *(q + x for x in "市都府県" if not q.isascii())):
        j = _get_json("https://geocoding-api.open-meteo.com/v1/search", {"name": name, "count": 8, "language": "ja"})
        if j.get("results"):
            break
    return [{"name": ", ".join(x for x in (r.get("name"), r.get("admin1"), r.get("country")) if x),
             "lat": r["latitude"], "lon": r["longitude"]} for r in j.get("results") or []]


def fetch_weather(loc: dict, start: str | None = None, end: str | None = None) -> list[dict]:
    """日ごとの天気。start/end (YYYY-MM-DD) を渡すと過去の記録 (archive)、なければ直近 92 日 + 今日。"""
    base = {"latitude": loc["lat"], "longitude": loc["lon"], "daily": WEATHER_DAILY, "timezone": "auto"}
    if start:
        j = _get_json("https://archive-api.open-meteo.com/v1/archive", {**base, "start_date": start, "end_date": end})
    else:
        try:
            j = _get_json("https://api.open-meteo.com/v1/forecast", {**base, "past_days": 92, "forecast_days": 1})
        except OSError:
            # api.open-meteo.com だけ TLS の接続がタイムアウトするネットワークがあったので、同じ値を返す別のホストで取り直す
            today = time.strftime("%Y-%m-%d")
            j = _get_json("https://historical-forecast-api.open-meteo.com/v1/forecast",
                          {**base, "start_date": time.strftime("%Y-%m-%d", time.localtime(time.time() - 92 * 86400)),
                           "end_date": today})
    d = j.get("daily") or {}
    keys = ["weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_sum",
            "sunshine_duration", "shortwave_radiation_sum"]
    out = []
    for i, day in enumerate(d.get("time") or []):
        v = [(d.get(k) or [None] * (i + 1))[i] for k in keys]
        if v[0] is None:  # まだ値の無い日 (archive の直近数日など)
            continue
        out.append({"day": day, "code": v[0], "tmax": v[1], "tmin": v[2], "precip": v[3],
                    "sun_h": round(v[4] / 3600, 1) if v[4] is not None else None, "rad_mj": v[5]})
    return out


def weather_loop(db: "HistoryDB") -> None:
    """場所が設定されていれば、3 時間ごと (と場所を変えたとき) に天気を取って weather 表に書く。
    初回と場所を変えたときは、記録のある最初の日までさかのぼって埋める。"""
    while True:
        loc = load_settings().get("location")
        wait = WEATHER_EVERY
        try:
            if loc:
                key = f"{loc['lat']},{loc['lon']}"
                if db.weather_place() != key:  # 場所が変わったら前の場所の天気は捨てる
                    db.clear_weather(key)
                rows = fetch_weather(loc)
                first = db.first_day()
                have = db.weather_first_day()
                recent0 = rows[0]["day"] if rows else None
                if first and recent0 and first < recent0 and (have is None or have > first):
                    rows += fetch_weather(loc, first, recent0)
                db.put_weather(rows)
        except Exception as e:  # 通信できない時は 5 分後にまた試す (3 時間待つと、その日の分が空いたままになる)
            LOG.warning("天気を取れませんでした: %s", e)
            wait = 300
        WEATHER_WAKE.wait(wait)
        WEATHER_WAKE.clear()


def apply_settings(cur: dict) -> None:
    global SAMPLE_INTERVAL
    SAMPLE_INTERVAL = int(cur.get("sample_interval") or 10)


def device_id(address: str) -> str:
    return "ble-" + hashlib.sha1(address.encode()).hexdigest()[:8]


def cloud_device_id(sn: str) -> str:
    return "cloud-" + hashlib.sha1(sn.encode()).hexdigest()[:8]


def dev_source(d: dict) -> str:
    """BLE とクラウドのどちらを持っているか: auto=両方 / ble / cloud。"""
    if d.get("address") and d.get("cloud_sn"):
        return "auto"
    return "cloud" if d.get("cloud_sn") else "ble"


# ───────────────────────── 登録機器 ─────────────────────────
class Registry:
    """登録した機器の一覧 (devices.json)。"""

    def __init__(self, persist: bool = True) -> None:
        self.persist = persist
        self.lock = threading.Lock()
        self.devices: list[dict] = []
        if persist:
            try:
                self.devices = json.loads((DATA_DIR / "devices.json").read_text())
            except (OSError, ValueError):
                self.devices = []

    def _save(self) -> None:
        if not self.persist:
            return
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = DATA_DIR / "devices.json.tmp"
        tmp.write_text(json.dumps(self.devices, ensure_ascii=False, indent=2))
        tmp.replace(DATA_DIR / "devices.json")

    def list(self) -> list[dict]:
        with self.lock:
            return [dict(d) for d in self.devices]

    def get(self, dev_id: str) -> dict | None:
        with self.lock:
            return next((dict(d) for d in self.devices if d["id"] == dev_id), None)

    def add(self, dev: dict) -> bool:
        with self.lock:
            if any(d["id"] == dev["id"] for d in self.devices):
                return False
            self.devices.append(dev)
            self._save()
            return True

    def remove(self, dev_id: str) -> bool:
        with self.lock:
            n = len(self.devices)
            self.devices = [d for d in self.devices if d["id"] != dev_id]
            self._save()
            return len(self.devices) != n

    def update(self, dev_id: str, **fields) -> bool:
        with self.lock:
            for d in self.devices:
                if d["id"] == dev_id:
                    for k, v in fields.items():
                        if v is None:
                            d.pop(k, None)
                        else:
                            d[k] = v
                    d["source"] = dev_source(d)
                    self._save()
                    return True
            return False

    def rename(self, dev_id: str, name: str) -> bool:
        with self.lock:
            for d in self.devices:
                if d["id"] == dev_id:
                    d["name"] = name
                    self._save()
                    return True
            return False


# ───────────────────────── 履歴 (SQLite) ─────────────────────────
# 内訳の列 (log テーブルの列名 = ポートのキー)。列を足すときはここに足す (既存の DB には自動で ALTER)
PORT_COLS = [key for _a, key, _l, _d in PORT_PROPS] + ["in_usb", "xfer_in", "xfer_out", "xfer_loss", "other_in", "other_out"]
CHG_CODE = {"IDLE": 0, "CHARGING": 1, "DISCHARGING": 2}
# 節電記録 (savings) の Transfer の列。ロスは送り側の行 / 受け側の行のぶんを分けて持つ
SAVINGS_XFER_COLS = ["wh_xfer_in", "wh_xfer_out", "wh_xfer_loss_in", "wh_xfer_loss_out"]
SCHEMA_VERSION = 2


class HistoryDB:
    """履歴 (スキーマ v2)。

    log          メインのログ。1行 = 1機器の1時点。機器は整数ID、時刻はUNIX秒。
                 pct 残量 / pin 入力W (0Wは NULL) / pout 出力W / chg 0待機 1充電 2放電 / temp 温度 /
                 以降はポート別の W (0W は NULL = 記録しない)。主キー (dev, ts) の WITHOUT ROWID で索引も兼ねる。
    devices      機器の台帳。整数ID ↔ 機器ID文字列 (BLEアドレス由来)、名前、機種、接続設定 (JSON)。
    log_columns  log の各列が何を指すか (列名・表示名・方向・単位)。
    """

    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.path = path
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self._ids: dict[str, int] = {}
        self._migrate()
        self._ensure()
        self._ids = {d: i for d, i in self.db.execute("SELECT dev, id FROM devices")}
        # 記録の書き直しは 1 回だけ。user_version はスキーマの版数に使っているので、別の表に印を残す
        self.db.execute("CREATE TABLE IF NOT EXISTS fixes(name TEXT PRIMARY KEY, at INTEGER)")
        if not self.db.execute("SELECT 1 FROM fixes WHERE name='sharing_multi_port'").fetchone():
            self.fix_sharing()
            self.db.execute("INSERT INTO fixes VALUES('sharing_multi_port', ?)", (int(time.time()),))
            self.db.commit()

    # ── 天気 ──
    def weather_place(self) -> str | None:
        with self.lock:
            r = self.db.execute("SELECT v FROM kv WHERE k='weather_place'").fetchone()
        return r[0] if r else None

    def clear_weather(self, place: str) -> None:
        with self.lock:
            self.db.execute("DELETE FROM weather")
            self.db.execute("INSERT OR REPLACE INTO kv VALUES('weather_place', ?)", (place,))
            self.db.commit()

    def put_weather(self, rows: list[dict]) -> None:
        now = int(time.time())
        with self.lock:
            self.db.executemany("INSERT OR REPLACE INTO weather VALUES(?,?,?,?,?,?,?,?)",
                                [(r["day"], r["code"], r["tmax"], r["tmin"], r["precip"], r["sun_h"], r["rad_mj"], now)
                                 for r in rows])
            self.db.commit()

    def weather_rows(self, d0: str, d1: str) -> dict[str, dict]:
        with self.lock:
            rs = self.db.execute("SELECT day, code, tmax, tmin, precip, sun_h, rad_mj FROM weather "
                                 "WHERE day>=? AND day<? ORDER BY day", (d0, d1)).fetchall()
        return {r[0]: {"code": r[1], "tmax": r[2], "tmin": r[3], "precip": r[4], "sun_h": r[5], "rad_mj": r[6]}
                for r in rs}

    def weather_first_day(self) -> str | None:
        with self.lock:
            return self.db.execute("SELECT MIN(day) FROM weather").fetchone()[0]

    def first_day(self) -> str | None:
        with self.lock:
            t = self.db.execute("SELECT MIN(ts) FROM log").fetchone()[0]
        return time.strftime("%Y-%m-%d", time.localtime(t)) if t else None

    def fix_sharing(self) -> dict:
        """記録済みの log を、いまの find_sharing で見直して、機器どうしの給電を Transfer に書き直す (1 回だけ)。
        2 本以上のケーブルで送っていた区間は、以前の判定 (ポート 1 本ずつの比較) では見つからず、
        送り側の出力・受け側の入力のまま残っているため。同じ時刻の行どうしを対応づける
        (サンプルは全機器を同じ時刻で書き、間引きも同じ区切りでそろうので、時刻が一致する)。
        書き直した (機器, 日) の節電記録は消し、次の refresh_savings で作り直させる。"""
        port_cols = [c for c in PORT_COLS if not c.startswith("xfer_")]
        sel = ", ".join(f"COALESCE({c},0)" for c in port_cols)
        with self.lock:
            rows = self.db.execute(
                f"SELECT dev, ts, pin, pout, COALESCE(xfer_in,0), COALESCE(xfer_out,0), COALESCE(xfer_loss,0), {sel} "
                "FROM log WHERE ts IN (SELECT ts FROM log GROUP BY ts HAVING COUNT(*) > 1) ORDER BY ts").fetchall()
        off = time.localtime().tm_gmtoff or 0
        updates, touched = [], set()
        for ts, grp in itertools.groupby(rows, key=lambda r: r[1]):
            grp = {r[0]: r for r in grp}
            vals = {str(d): {"state": "connected",
                             "ports": [{"key": c, "dir": PORT_INFO[c][1], "w": w}
                                       for c, w in zip(port_cols, r[7:]) if w > 0 and c in PORT_INFO]}
                    for d, r in grp.items()}
            for d, shares in find_sharing(vals).items():
                r = grp[int(d)]
                pin, pout, x_in, x_out, x_loss = r[2], r[3], r[4], r[5], r[6]
                cols = {}
                for x in shares:
                    cols[x["key"]] = None
                    if x["dir"] == "in":
                        x_in += x["recv"]
                        pin = (pin or 0) - x["w"]
                    else:
                        x_out += x["recv"]
                        pout = (pout or 0) - x["w"]
                    x_loss += max(0, x["sent"] - x["recv"])
                cols.update({"pin": pin if pin and pin > 0 else None, "pout": max(0, pout or 0),
                             "xfer_in": x_in or None, "xfer_out": x_out or None, "xfer_loss": x_loss or None})
                updates.append((int(d), ts, cols))
                touched.add((int(d), time.strftime("%Y-%m-%d", time.gmtime(ts + off))))
        with self.lock:
            for d, ts, cols in updates:
                self.db.execute(f"UPDATE log SET {', '.join(f'{c}=?' for c in cols)} WHERE dev=? AND ts=?",
                                [*cols.values(), d, ts])
            self.db.executemany("DELETE FROM savings WHERE dev=? AND day=?", sorted(touched))
            self.db.commit()
        if updates:
            LOG.info("機器どうしの給電を Transfer に書き直しました: %s 行 (%s)", len(updates),
                     ", ".join(sorted({day for _d, day in touched})))
        return {"rows": len(updates), "days": sorted(touched)}

    # ── スキーマ ──
    def _ensure(self) -> None:
        cols = ",".join(f"{c} INTEGER" for c in PORT_COLS)
        self.db.execute("CREATE TABLE IF NOT EXISTS devices(id INTEGER PRIMARY KEY, dev TEXT UNIQUE, name TEXT, "
                        "model TEXT, config TEXT)")
        self.db.execute("CREATE TABLE IF NOT EXISTS log(dev INTEGER, ts INTEGER, pct INTEGER, pin INTEGER, "
                         f"pout INTEGER, chg INTEGER, temp INTEGER, {cols}, PRIMARY KEY(dev, ts)) WITHOUT ROWID")
        have = {r[1] for r in self.db.execute("PRAGMA table_info(log)")}
        for c in PORT_COLS:
            if c not in have:
                self.db.execute(f"ALTER TABLE log ADD COLUMN {c} INTEGER")
        # 節電記録: 日ごと・機器ごと。log を間引いたり消したりしても残る (電力量 Wh と、ソーラーで賄えた分)
        self.db.execute("CREATE TABLE IF NOT EXISTS savings(dev INTEGER, day TEXT, wh_in REAL, wh_out REAL, wh_solar REAL, "
                        "wh_ac REAL, wh_saved REAL, wh_loss REAL, hours REAL, updated INTEGER, PRIMARY KEY(dev, day)) WITHOUT ROWID")
        have_s = {r[1] for r in self.db.execute("PRAGMA table_info(savings)")}
        for c in SAVINGS_XFER_COLS:
            if c not in have_s:
                self.db.execute(f"ALTER TABLE savings ADD COLUMN {c} REAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS log_columns(col TEXT PRIMARY KEY, label TEXT, dir TEXT, unit TEXT, note TEXT)")
        # 天気: 設定した場所の日ごとの値 (Open-Meteo)。code は WMO の天気コード、sun_h は日照時間、rad_mj は日射量 MJ/m²
        self.db.execute("CREATE TABLE IF NOT EXISTS weather(day TEXT PRIMARY KEY, code INTEGER, tmax REAL, tmin REAL, "
                        "precip REAL, sun_h REAL, rad_mj REAL, updated INTEGER)")
        self.db.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
        rows = [("ts", "時刻", None, "UNIX秒", ""), ("dev", "機器 (devices.id)", None, "", ""),
                ("pct", "残量", None, "%", ""), ("pin", "入力 合計", "in", "W", "0W のときは NULL (記録しない)"),
                ("pout", "出力 合計", "out", "W", ""), ("chg", "状態", None, "", "0=待機 1=充電 2=放電"),
                ("temp", "温度", None, "℃", "")]
        rows += [(k, lb, d, "W", "ポート別。0W のときは NULL (記録しない)") for _a, k, lb, d in PORT_PROPS]
        rows += [("in_usb", PORT_INFO["in_usb"][0], "in", "W", "USB ポートからの入力 (双方向ポートの入力側)"),
                 ("xfer_in", PORT_INFO["xfer_in"][0], "in", "W", "別の機器からの給電。pin には含めない"),
                 ("xfer_out", PORT_INFO["xfer_out"][0], "out", "W",
                  "別の機器への給電 (相手が受け取った W)。pout には含めない"),
                 ("xfer_loss", PORT_INFO["xfer_loss"][0], "loss", "W",
                  "給電の送り出し W − 受け取り W。送り側・受け側の両方に記録。pin / pout には含めない"),
                 ("other_in", *PORT_INFO["other_in"][:1], "in", "W", "合計とポート合計の差分"),
                 ("other_out", *PORT_INFO["other_out"][:1], "out", "W", "合計とポート合計の差分")]
        self.db.executemany("INSERT OR REPLACE INTO log_columns VALUES(?,?,?,?,?)", rows)
        self.db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        self.db.commit()

    def _migrate(self) -> None:
        """v1 (samples + ports の縦持ち、機器IDが文字列) → v2。元のファイルは .v1.bak に残す。"""
        has = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if "samples" not in has:
            return
        LOG.info("履歴を新しい形式に移します (元のファイルは %s.v1.bak に残します)…", self.path.name)
        self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        import shutil
        shutil.copy2(self.path, str(self.path) + ".v1.bak")
        self._ensure()
        old_meta = {r[0]: r[1:] for r in self.db.execute("SELECT dev,name,model FROM meta")} if "meta" in has else {}
        cols = ",".join(PORT_COLS)
        pv_sel = ",".join(f"SUM(CASE WHEN port='{c}' THEN w END) AS {c}" for c in PORT_COLS)
        pv_use = ",".join(f"pv.{c}" for c in PORT_COLS)
        chg = "CASE s.chg " + " ".join(f"WHEN '{k}' THEN {v}" for k, v in CHG_CODE.items()) + " END"
        for (dev,) in self.db.execute("SELECT DISTINCT dev FROM samples").fetchall():
            nm, md = old_meta.get(dev, (None, None))
            self.db.execute("INSERT OR IGNORE INTO devices(dev,name,model) VALUES(?,?,?)", (dev, nm, md))
            did = self.db.execute("SELECT id FROM devices WHERE dev=?", (dev,)).fetchone()[0]
            self.db.execute(
                f"INSERT OR REPLACE INTO log(dev,ts,pct,pin,pout,chg,temp,{cols}) "
                f"SELECT ?, s.ts, s.pct, s.pin, s.pout, {chg}, s.temp, {pv_use} "
                f"FROM samples s LEFT JOIN (SELECT ts, {pv_sel} FROM ports WHERE dev=? GROUP BY ts) pv ON pv.ts=s.ts "
                "WHERE s.dev=?", (did, dev, dev))
            self.db.commit()
        n_old = self.db.execute("SELECT COUNT(*) FROM samples").fetchone()[0]
        n_new = self.db.execute("SELECT COUNT(*) FROM log").fetchone()[0]
        if n_new < n_old:  # 取りこぼし (同じ (機器, 時刻) の重複など) が想定より多い時は消さずに止める
            LOG.warning("移行後の行数が少ない (%s → %s)。古い表は残します", n_old, n_new)
            return
        for t in ("samples", "ports", "meta"):
            self.db.execute(f"DROP TABLE IF EXISTS {t}")
        self.db.commit()
        try:
            self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            self.db.execute("VACUUM")
        except sqlite3.Error as e:
            LOG.warning("VACUUM に失敗: %s", e)
        LOG.info("履歴の移行が終わりました: %s 行", n_new)

    def _id(self, dev: str) -> int | None:
        return self._ids.get(dev)

    def _id_or_new(self, dev: str) -> int:
        i = self._ids.get(dev)
        if i is None:
            cur = self.db.execute("INSERT OR IGNORE INTO devices(dev) VALUES(?)", (dev,))
            i = self.db.execute("SELECT id FROM devices WHERE dev=?", (dev,)).fetchone()[0]
            self._ids[dev] = i
        return i

    # 履歴は自動では消えない。消すのは delete() を人が呼んだときだけ (画面の「履歴を削除」)。
    def storage(self) -> dict:
        """履歴の容量と中身。ファイルは本体 + WAL + SHM の合計 (ディスク上の実際の大きさ)。"""
        size = 0
        for suf in ("", "-wal", "-shm"):
            try:
                size += os.path.getsize(str(self.path) + suf)
            except OSError:
                pass
        with self.lock:
            n_s, oldest, newest = self.db.execute("SELECT COUNT(*), MIN(ts), MAX(ts) FROM log").fetchone()
            per = self.db.execute(
                "SELECT d.dev, COUNT(*), MIN(l.ts), MAX(l.ts), d.name FROM log l JOIN devices d ON d.id=l.dev "
                "GROUP BY l.dev").fetchall()
        return {
            "bytes": size, "samples": n_s, "port_rows": 0, "oldest": oldest, "newest": newest,
            "bytes_per_row": round(size / n_s, 1) if n_s else None,
            "devices": [{"id": d, "name": nm or d, "samples": c, "oldest": o, "newest": n} for d, c, o, n, nm in per],
        }

    def delete(self, dev: str | None = None, before: int | None = None) -> int:
        """履歴を消す。dev=None で全機器、before (UNIX秒) を渡すとそれより古いものだけ。消した行数を返す。"""
        where, args = [], []
        if dev:
            i = self._id(dev)
            if i is None:
                return 0
            where.append("dev = ?")
            args.append(i)
        if before is not None:
            where.append("ts < ?")
            args.append(int(before))
        cond = (" WHERE " + " AND ".join(where)) if where else ""
        with self.lock:
            n = self.db.execute("DELETE FROM log" + cond, args).rowcount
            self.db.commit()
            # ファイルを実際に小さくする (WAL を畳んでから VACUUM)
            try:
                self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                self.db.execute("VACUUM")
                self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            except sqlite3.Error as e:
                LOG.warning("VACUUM に失敗: %s", e)
        return n

    def count(self) -> int:
        with self.lock:
            return self.db.execute("SELECT COUNT(*) FROM log").fetchone()[0]

    def set_meta(self, dev: str, name: str, model: str, config: dict | None = None) -> None:
        with self.lock:
            i = self._id_or_new(dev)
            if config is None:
                self.db.execute("UPDATE devices SET name=?, model=? WHERE id=?", (name, model, i))
            else:
                self.db.execute("UPDATE devices SET name=?, model=?, config=? WHERE id=?",
                                (name, model, json.dumps(config, ensure_ascii=False), i))
            self.db.commit()

    def insert(self, samples: list[tuple], ports: list[tuple]) -> None:
        """samples: (ts, dev, pct, pin, pout, solar(未使用), temp, chg文字列)  ports: (ts, dev, key, w)。
        呼び出し側の形はそのままで、ここで1行にまとめる。"""
        port_by: dict[tuple, dict] = {}
        for ts, dev, key, w in ports:
            if key in PORT_COLS and w:
                port_by.setdefault((ts, dev), {})[key] = w
        rows = []
        with self.lock:
            for ts, dev, pct, pin, pout, _solar, temp, chg in samples:
                pv = port_by.get((ts, dev), {})
                rows.append((self._id_or_new(dev), ts, pct, pin, pout, CHG_CODE.get(chg or ""), temp,
                             *[pv.get(c) for c in PORT_COLS]))
            if rows:
                q = ",".join("?" * (7 + len(PORT_COLS)))
                self.db.executemany(
                    f"INSERT OR REPLACE INTO log(dev,ts,pct,pin,pout,chg,temp,{','.join(PORT_COLS)}) VALUES({q})", rows)
            self.db.commit()

    def devices(self) -> list[dict]:
        with self.lock:
            q = ("SELECT d.dev, COALESCE(d.name, d.dev), COALESCE(d.model,''), MIN(l.ts), MAX(l.ts) "
                 "FROM log l JOIN devices d ON d.id = l.dev GROUP BY l.dev")
            return [{"id": r[0], "name": r[1], "model": r[2], "first": r[3], "last": r[4]}
                    for r in self.db.execute(q).fetchall()]

    def raw(self, dev: str | None, t0: float, t1: float, limit: int) -> dict:
        """解析用: log の行を間引かず全列そのまま返す (列の意味は columns_info)。dev なしなら全機器。
        limit を超える分は新しい側を切る (truncated で分かる)。"""
        with self.lock:
            cols = [r[1] for r in self.db.execute("PRAGMA table_info(log)") if r[1] != "dev"]
            devs = self.db.execute("SELECT id, dev, COALESCE(name, dev), COALESCE(model,'') FROM devices").fetchall()
            if dev:
                devs = [d for d in devs if d[1] == dev]
            ids = {d[0]: d[1] for d in devs}
            rows = []
            if ids:
                marks = ",".join("?" * len(ids))
                rows = self.db.execute(f"SELECT dev, {', '.join(cols)} FROM log WHERE dev IN ({marks}) AND ts>=? AND ts<=? "
                                       f"ORDER BY ts LIMIT ?", [*ids, int(t0), int(t1), limit + 1]).fetchall()
            info = {r[0]: {"label": r[1], "dir": r[2], "unit": r[3], "note": r[4]}
                    for r in self.db.execute("SELECT col, label, dir, unit, note FROM log_columns")}
        return {"devices": [{"id": d[1], "name": d[2], "model": d[3], "capacity_wh": MODEL_WH.get(d[3])} for d in devs],
                "columns": ["dev", *cols], "columns_info": info, "truncated": len(rows) > limit,
                "rows": [[ids[r[0]], *r[1:]] for r in rows[:limit]]}

    def series(self, dev: str, t0: float, t1: float, bucket: int) -> list[list]:
        """[時刻, 残量%, 入力W, 出力W, 入力最大, 出力最大]。入力が全部 0W (NULL) の区間は入力が None。"""
        i = self._id(dev)
        if i is None:
            return []
        q = (
            "SELECT CAST(ts/? AS INTEGER)*? AS t, AVG(pct), SUM(pin)*1.0/COUNT(*), AVG(pout), MAX(pin), MAX(pout) "
            "FROM log WHERE dev=? AND ts>=? AND ts<=? GROUP BY 1 ORDER BY 1"
        )
        with self.lock:
            rows = self.db.execute(q, (bucket, bucket, i, int(t0), int(t1))).fetchall()
        return [[r[0], round(r[1], 1), None if r[2] is None else round(r[2]), round(r[3]), r[4], r[5]] for r in rows]

    def port_series(self, dev: str, t0: float, t1: float, bucket: int) -> dict[str, list[list]]:
        """ポートごとの [時刻, 平均W]。記録がない区間は 0W。"""
        i = self._id(dev)
        if i is None:
            return {}
        sums = ",".join(f"SUM({c})" for c in PORT_COLS)
        with self.lock:
            rows = self.db.execute(
                f"SELECT CAST(ts/? AS INTEGER)*?, COUNT(*), {sums} FROM log WHERE dev=? AND ts>=? AND ts<=? GROUP BY 1",
                (bucket, bucket, i, int(t0), int(t1))).fetchall()
        out: dict[str, list[list]] = {}
        for r in rows:
            t, n = r[0], max(1, r[1])
            for c, total in zip(PORT_COLS, r[2:]):
                if total:
                    out.setdefault(c, []).append([t, round(total / n)])
        for v in out.values():
            v.sort()
        return out

    def port_totals(self, dev: str, t0: float, t1: float) -> list[dict]:
        i = self._id(dev)
        if i is None:
            return []
        # 電力量 = 各行の W × その行から次の行までの秒数 (欠測は GAP_CAP で打ち切り)。間隔が混ざっていても正しい
        agg = ",".join(f"SUM({c}*dt),COUNT({c}),MAX({c}),SUM({c})" for c in PORT_COLS)
        sel = ",".join(PORT_COLS)
        with self.lock:
            r = self.db.execute(
                f"SELECT {agg} FROM (SELECT {sel}, MIN(COALESCE(LEAD(ts) OVER (ORDER BY ts) - ts, {BASE_TICK}), {GAP_CAP}) AS dt "
                "FROM log WHERE dev=? AND ts>=? AND ts<=?)", (i, int(t0), int(t1))).fetchone()
        out = []
        for k, port in enumerate(PORT_COLS):
            wsec, n, mx, total = r[4 * k: 4 * k + 4]
            if not n:
                continue
            label, direction = PORT_INFO.get(port, (port, "out"))
            out.append({
                "key": port, "label": label, "dir": direction,
                "wh": round((wsec or 0) / 3600, 1),
                "avg_active": round(total / n), "max": mx,
            })
        return sorted(out, key=lambda r: -r["wh"])

    def daily(self, t0: float, t1: float, dev: str | None = None) -> list[dict]:
        """日ごと (このMacの時刻の日付) の電力量 Wh と、ソーラーで賄えた分 (節約)。

        節約の数え方 (クレジット方式): ソーラー入力の Wh をクレジットとして貯め、出力はまずクレジットから
        消化する (AC入力が混じっていても、出力はソーラー分を先に使う)。出力のうちクレジットで賄えた分が節約。
        待機・変換ロス (入力 − 出力 − 残量の増減) はクレジットから差し引く。電池に入っている量が上限。
        直近 3 日ぶんから積み上げる (クレジットは 0 から開始)。"""
        off = time.localtime().tm_gmtoff or 0
        with self.lock:
            devs = self.db.execute("SELECT id, dev, COALESCE(model,'') FROM devices").fetchall()
        if dev:
            devs = [d for d in devs if d[1] == dev]
        cols = ("ts, pct, COALESCE(pin,0), pout, COALESCE(solar,0), "
                "COALESCE(xfer_in,0), COALESCE(xfer_out,0), COALESCE(xfer_loss,0)")
        out: list[dict] = []
        for did, dname, model in devs:
            cap = MODEL_WH.get(model, 0)
            with self.lock:
                rows = self.db.execute(f"SELECT {cols} FROM log WHERE dev=? AND ts>=? AND ts<=? ORDER BY ts",
                                       (did, int(t0) - 3 * 86400, int(t1))).fetchall()
            days: dict[int, dict] = {}
            for prev, cur in zip(rows, rows[1:]):
                d = (prev[0] + off) // 86400
                m = days.setdefault(d, {"in": 0.0, "out": 0.0, "solar": 0.0, "ac": 0.0, "hours": 0.0,
                                        "x_in": 0.0, "x_out": 0.0, "xl_in": 0.0, "xl_out": 0.0,
                                        "p0": prev[1], "p1": cur[1], "pmin": prev[1], "pmax": prev[1], "rows": []})
                dt = min(cur[0] - prev[0], GAP_CAP)
                k = dt / 3600
                sol = min(prev[4], prev[2]) if prev[4] else 0
                m["in"] += prev[2] * k
                m["out"] += prev[3] * k
                m["solar"] += sol * k
                m["ac"] += max(0, prev[2] - sol) * k
                m["hours"] += k
                # ロスは送り側・受け側の両方の行に入っているので、行の向きで分けておく
                # (全機器の合計で、同じロスを 2 回数えないように)
                m["x_in"] += prev[5] * k
                m["x_out"] += prev[6] * k
                m["xl_out" if prev[6] else "xl_in"] += prev[7] * k
                m["p1"] = cur[1]
                m["pmin"], m["pmax"] = min(m["pmin"], cur[1]), max(m["pmax"], cur[1])
                m["rows"].append((sol, prev[3], k, prev[1]))
            credit = 0.0
            for d in sorted(days):
                m = days[d]
                # この日の待機・変換ロス (W): 入力 − 出力 − 電池の増減。電池容量が分からない機種は 0 とする
                d_e = (m["p1"] - m["p0"]) / 100 * cap
                # 給電ぶんは入力・出力から引いてあるが、電池の増減には入っているので戻す
                # (送り側は相手に届いた分 + 途中のロスだけ電池が減る)。戻さないと、送ったぶんが待機ロスに見える
                bat_in, bat_out = m["in"] + m["x_in"], m["out"] + m["x_out"] + m["xl_out"]
                loss = max(0.0, bat_in - bat_out - d_e) if cap else 0.0
                loss_w = loss / m["hours"] if m["hours"] else 0.0
                saved = 0.0
                for sol, po, k, pct in m["rows"]:
                    credit += sol * k
                    used = min(po * k, credit)
                    credit -= used
                    saved += used
                    credit = max(0.0, credit - loss_w * k)
                    if cap:
                        credit = min(credit, pct / 100 * cap)
                if t0 - 86400 <= d * 86400 - off <= t1:
                    out.append({"dev": dname, "day": time.strftime("%Y-%m-%d", time.gmtime(d * 86400)),
                                "wh_in": round(m["in"], 1), "wh_out": round(m["out"], 1),
                                "wh_solar": round(m["solar"], 1), "wh_ac": round(m["ac"], 1),
                                "wh_saved": round(saved, 1), "wh_loss": round(loss, 1),
                                "wh_xfer_in": round(m["x_in"], 1), "wh_xfer_out": round(m["x_out"], 1),
                                "wh_xfer_loss_in": round(m["xl_in"], 1), "wh_xfer_loss_out": round(m["xl_out"], 1),
                                "pct_min": m["pmin"], "pct_max": m["pmax"], "hours": round(m["hours"], 1)})
        return sorted(out, key=lambda r: r["day"])

    def refresh_savings(self, days_back: int | None = None) -> int:
        """log から日ごとの節電記録を計算して savings に書く。days_back=None なら全期間 (初回の取り込み)。"""
        now = time.time()
        with self.lock:
            first = self.db.execute("SELECT MIN(ts) FROM log").fetchone()[0]
        if first is None:
            return 0
        t0 = now - days_back * 86400 if days_back is not None else first - 86400
        rows = self.daily(t0, now)
        # 直近 RAW_KEEP_DAYS 日は何度でも計算し直す。それより古い日は「確定」。すでにあれば上書きしない
        # (そこは間引き済みのログから再計算すると精度が落ちるため)
        off = time.localtime().tm_gmtoff or 0
        cutoff = time.strftime("%Y-%m-%d", time.gmtime(now + off - RAW_KEEP_DAYS * 86400))
        with self.lock:
            ids = {d: i for d, i in self.db.execute("SELECT dev, id FROM devices")}
            names = "dev,day,wh_in,wh_out,wh_solar,wh_ac,wh_saved,wh_loss,hours,updated," + ",".join(SAVINGS_XFER_COLS)
            marks = ",".join("?" * (10 + len(SAVINGS_XFER_COLS)))
            for verb, part in (("REPLACE", [r for r in rows if r["day"] >= cutoff]),
                               ("IGNORE", [r for r in rows if r["day"] < cutoff])):
                self.db.executemany(
                    f"INSERT OR {verb} INTO savings({names}) VALUES({marks})",
                    [(ids[r["dev"]], r["day"], r["wh_in"], r["wh_out"], r["wh_solar"], r["wh_ac"], r["wh_saved"],
                      r["wh_loss"], r["hours"], int(now), *[r[c] for c in SAVINGS_XFER_COLS])
                     for r in part if r["dev"] in ids])
            # 確定済みの日も、Transfer の列を足す前に書いた行 (NULL) だけは埋める (ほかの列は上書きしない)
            sets = ",".join(f"{c}=?" for c in SAVINGS_XFER_COLS)
            self.db.executemany(
                f"UPDATE savings SET {sets} WHERE dev=? AND day=? AND {SAVINGS_XFER_COLS[0]} IS NULL",
                [(*[r[c] for c in SAVINGS_XFER_COLS], ids[r["dev"]], r["day"])
                 for r in rows if r["day"] < cutoff and r["dev"] in ids])
            self.db.commit()
        return len(rows)

    def thin_old(self) -> dict:
        """節電記録を確定させたあと、RAW_KEEP_DAYS 日より前のログを THIN_INTERVAL 秒の平均に間引く。"""
        self.refresh_savings(RAW_KEEP_DAYS + 1)
        off = time.localtime().tm_gmtoff or 0
        now = time.time()
        local_midnight = int((now + off) // 86400 * 86400 - off)
        return self.compact(THIN_INTERVAL, local_midnight - RAW_KEEP_DAYS * 86400)

    def savings_rows(self, t0: float, t1: float, dev: str | None = None) -> list[dict]:
        off = time.localtime().tm_gmtoff or 0
        d0 = time.strftime("%Y-%m-%d", time.gmtime(t0 + off))
        d1 = time.strftime("%Y-%m-%d", time.gmtime(t1 + off))
        xs = "".join(f", COALESCE(s.{c},0)" for c in SAVINGS_XFER_COLS)
        q = ("SELECT d.dev, s.day, s.wh_in, s.wh_out, s.wh_solar, s.wh_ac, s.wh_saved, s.wh_loss, s.hours" + xs +
             " FROM savings s JOIN devices d ON d.id=s.dev WHERE s.day>=? AND s.day<?")
        args: list = [d0, d1]
        if dev:
            q += " AND d.dev=?"
            args.append(dev)
        with self.lock:
            rs = self.db.execute(q + " ORDER BY s.day", args).fetchall()
        return [{"dev": r[0], "day": r[1], "wh_in": r[2], "wh_out": r[3], "wh_solar": r[4], "wh_ac": r[5],
                 "wh_saved": r[6], "wh_loss": r[7], "hours": r[8], **dict(zip(SAVINGS_XFER_COLS, r[9:]))} for r in rs]

    def compact(self, interval: int, before: int | None = None) -> dict:
        """すでにある履歴を interval 秒ごとの平均に間引く (before より古い行だけ。None なら全部)。
        電力は平均、残量・温度・状態は最後の値。電力量 (Wh) はほぼ変わらない。途中で失敗したら全部元に戻す。"""
        interval = max(10, int(interval))
        cols = ["pin", "pout", *PORT_COLS]
        avg = ",".join(f"CAST(ROUND(AVG({c})) AS INTEGER) AS {c}" for c in cols)
        cond = "" if before is None else f" WHERE ts < {int(before)}"
        with self.lock:
            try:
                n0 = self.db.execute(f"SELECT COUNT(*) FROM log{cond}").fetchone()[0]
                self.db.execute("DROP TABLE IF EXISTS temp.log_new")
                # MAX(ts) が1つだけなので、pct / chg / temp は最後の行の値になる (SQLite の仕様)
                self.db.execute(f"CREATE TEMP TABLE log_new AS SELECT dev, MAX(ts) AS ts, pct, {avg}, chg, temp "
                                f"FROM log{cond} GROUP BY dev, ts / {interval}")
                self.db.execute(f"DELETE FROM log{cond}")
                self.db.execute(f"INSERT OR REPLACE INTO log(dev,ts,pct,{','.join(cols)},chg,temp) "
                                f"SELECT dev,ts,pct,{','.join(cols)},chg,temp FROM log_new")
                n1 = self.db.execute(f"SELECT COUNT(*) FROM log{cond}").fetchone()[0]
                self.db.execute("DROP TABLE temp.log_new")
                self.db.commit()
            except Exception:
                self.db.rollback()
                raise
            try:
                self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                self.db.execute("VACUUM")
                self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            except sqlite3.Error as e:
                LOG.warning("VACUUM に失敗: %s", e)
        return {"before": n0, "after": n1}

    def summary(self, dev: str, t0: float, t1: float) -> dict:
        i = self._id(dev)
        if i is None:
            return {"samples": 0}
        with self.lock:
            rows = self.db.execute(
                "SELECT ts, pct, pin, pout FROM log WHERE dev=? AND ts>=? AND ts<=? ORDER BY ts",
                (i, int(t0), int(t1)),
            ).fetchall()
        if not rows:
            return {"samples": 0}
        wh_in = wh_out = covered = 0.0
        for prev, cur in zip(rows, rows[1:]):
            dt = min(cur[0] - prev[0], GAP_CAP)  # 欠測区間は足さない
            wh_in += (prev[2] or 0) * dt / 3600  # 入力 NULL は 0W 扱い
            wh_out += prev[3] * dt / 3600
            covered += dt
        pcts = [r[1] for r in rows]
        return {
            "samples": len(rows),
            "wh_in": round(wh_in, 1),
            "wh_out": round(wh_out, 1),
            "pin_max": max((r[2] or 0) for r in rows),
            "pout_max": max(r[3] for r in rows),
            "pout_avg": round(sum(r[3] for r in rows) / len(rows)),
            "pct_min": min(pcts),
            "pct_max": max(pcts),
            "pct_start": rows[0][1],
            "pct_end": rows[-1][1],
            "covered_h": round(covered / 3600, 1),
            "first": rows[0][0],
            "last": rows[-1][0],
        }


# ───────────────────────── 現在値 ─────────────────────────
class Store:
    """機器ごとの現在値。データ源 (BLE / クラウド) がチャンネル別に書き、読むときに1つへまとめる。

    BLE がつながっていれば BLE (更新が速い)、そうでなければクラウドの値を使う。
    """

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.chans: dict[str, dict[str, dict]] = {}

    def update(self, dev_id: str, fields: dict, channel: str = "ble") -> None:
        with self.lock:
            c = self.chans.setdefault(dev_id, {}).setdefault(channel, {})
            c.update(fields)
            c["updated"] = time.time()

    def drop(self, dev_id: str) -> None:
        with self.lock:
            self.chans.pop(dev_id, None)

    def get(self, dev_id: str) -> dict:
        with self.lock:
            chans = {k: dict(v) for k, v in self.chans.get(dev_id, {}).items()}
        if not chans:
            return {}
        pick = next((k for k in ("ble", "cloud") if chans.get(k, {}).get("state") == "connected"), None)
        if pick is None:  # どちらも未接続: 一番新しい状況を見せる (理由が分かるように)
            pick = max(chans, key=lambda k: chans[k].get("updated", 0))
        out = dict(chans[pick])
        out["via"] = pick if out.get("state") == "connected" else None
        out["channels"] = {k: {"state": v.get("state"), "detail": v.get("detail", "")} for k, v in chans.items()}
        return out


# ───────────────────────── Bluetooth ─────────────────────────
class Ble:
    """スキャンと接続の交通整理。スキャン結果は数秒使い回し、接続は1台ずつ行う。

    SolixBLE の探索は「Anker 共通のサービスUUID」を広告している機器だけを拾う。
    機種によっては広告が違う可能性があるので、名前に solix / anker を含むものも候補にする。
    """

    NAME_KEYS = ("solix", "anker")

    def __init__(self) -> None:
        self.scan_lock = asyncio.Lock()
        self.connect_lock = asyncio.Lock()
        self._cache: tuple[list, float] = ([], 0.0)
        self.meta: dict[str, dict] = {}  # address -> {"uuid": bool, "rssi": int}

    async def discover(self, max_age: float = 6.0, timeout: int = 8) -> list:
        async with self.scan_lock:
            devs, ts = self._cache
            if time.time() - ts < max_age:
                return devs
            from bleak import BleakScanner
            from SolixBLE.const import UUID_IDENTIFIER

            found: dict[str, object] = {}

            def cb(device, adv) -> None:
                name = (device.name or getattr(adv, "local_name", None) or "").lower()
                uuid_ok = UUID_IDENTIFIER in (adv.service_uuids or [])
                if uuid_ok or any(k in name for k in self.NAME_KEYS):
                    found[device.address] = device
                    self.meta[device.address] = {"uuid": uuid_ok, "rssi": adv.rssi}

            async with BleakScanner(cb):
                await asyncio.sleep(timeout)
            devs = list(found.values())
            self._cache = (devs, time.time())
            return devs

    async def scan_all(self, timeout: int = 10) -> list[dict]:
        """診断用: 周りの Bluetooth 機器を全部出す (機種が見つからない原因調べ)。"""
        from bleak import BleakScanner
        from SolixBLE.const import UUID_IDENTIFIER

        seen: dict[str, dict] = {}

        def cb(device, adv) -> None:
            name = device.name or getattr(adv, "local_name", None) or ""
            seen[device.address] = {
                "address": device.address, "name": name, "rssi": adv.rssi,
                "uuid_match": UUID_IDENTIFIER in (adv.service_uuids or []),
                "service_uuids": list(adv.service_uuids or []),
                "manufacturer_ids": sorted(adv.manufacturer_data.keys()) if adv.manufacturer_data else [],
                "anker_like": any(k in name.lower() for k in self.NAME_KEYS),
            }

        async with BleakScanner(cb):
            await asyncio.sleep(timeout)
        return sorted(seen.values(), key=lambda d: (not (d["uuid_match"] or d["anker_like"]), -(d["rssi"] or -999)))


class FakeBle:
    """--demo 用。機器を探した体の結果を返す。"""

    class _D:
        def __init__(self, name: str, address: str) -> None:
            self.name, self.address = name, address

    async def discover(self, max_age: float = 6.0, timeout: int = 8) -> list:
        await asyncio.sleep(1.5)
        return [
            self._D("Anker SOLIX C1000 Gen 2", "DEMO-0001"),
            self._D("Anker SOLIX F3800", "DEMO-0002"),
            self._D("Anker SOLIX Prime Charger", "DEMO-0003"),
            self._D("Anker SOLIX C300 DC", "DEMO-A"),
        ]

    async def scan_all(self, timeout: int = 10) -> list[dict]:
        await asyncio.sleep(1)
        return [
            {"address": "DEMO-A", "name": "Anker SOLIX C300 DC", "rssi": -55, "uuid_match": True,
             "service_uuids": ["0000ff09-0000-1000-8000-00805f9b34fb"], "manufacturer_ids": [], "anker_like": True},
            {"address": "DEMO-X", "name": "", "rssi": -80, "uuid_match": False,
             "service_uuids": [], "manufacturer_ids": [76], "anker_like": False},
        ]


# ───────────────────────── データ源 ─────────────────────────
class Source:
    """機器1台分のデータ源。run() が止まらずに store を更新し続ける。"""

    async def run(self, store: Store) -> None:  # pragma: no cover
        raise NotImplementedError


def _get(dev, *names):
    """SolixBLE の値を安全に読む。未取得(-1)・未対応・例外は None。"""
    for n in names:
        try:
            v = getattr(dev, n)
        except Exception:
            continue
        if v is not None and v != -1:
            return v
    return None


def _sum(dev, *names):
    vals = [v for v in (_get(dev, n) for n in names) if isinstance(v, (int, float))]
    return sum(vals) if vals else None


def derive_charging(lib_status: str | None, p_in, p_out) -> str:
    if lib_status in ("CHARGING", "DISCHARGING", "IDLE"):
        return lib_status
    if p_in is None and p_out is None:
        return "UNKNOWN"
    if (p_in or 0) > (p_out or 0) + 5:
        return "CHARGING"
    return "DISCHARGING" if (p_out or 0) > 0 else "IDLE"


def build_ports(values: dict[str, int], p_in, p_out) -> list[dict]:
    """ポート別の値 (0Wは省く) を作る。合計との差が大きければ「その他」で埋める。"""
    ports = []
    sums = {"in": 0, "out": 0}
    for key, w in values.items():
        label, direction = PORT_INFO[key]
        sums[direction] += w
        if w > 0:
            ports.append({"key": key, "label": label, "dir": direction, "w": int(w)})
    for direction, total, key in (("in", p_in, "other_in"), ("out", p_out, "other_out")):
        if isinstance(total, (int, float)) and total - sums[direction] >= 3:
            ports.append({"key": key, "label": PORT_INFO[key][0], "dir": direction, "w": int(total - sums[direction])})
    return ports


def find_sharing(vals: dict[str, dict]) -> dict[str, list[dict]]:
    """機器どうしの「電力共有」を見つける。
    A の USB-C 出力と、B の USB 入力 (ソーラー以外の入力ポート) がほぼ同じ量なら、そのぶんは
    機器の間を行き来しているだけ (消費でも発電でもない) とみなす。戻り値: 機器ID → [{key, dir, w, peer}]"""
    out: dict[str, list[dict]] = {}
    live = {i: v for i, v in vals.items() if v.get("state") == "connected"}

    def used(dev: str, key: str) -> bool:
        return any(x["key"] == key for x in out.get(dev, []))

    def close(x: float, y: float) -> bool:
        return abs(x - y) <= max(SHARE_ABS_W, SHARE_REL * max(x, y))

    for b, vb in live.items():
        for pb in vb.get("ports") or []:
            if pb["dir"] != "in" or pb["key"] not in ("in_usb", "other_in", "ac_in") or pb["w"] < SHARE_MIN_W \
                    or used(b, pb["key"]):
                continue
            match = None
            for a, va in live.items():
                if a == b:
                    continue
                outs = [p for p in va.get("ports") or [] if p["dir"] == "out" and p["key"].startswith("usb_c")
                        and p["w"] >= SHARE_MIN_W and not used(a, p["key"])]
                # 受け側の USB 入力は全ポートの合計しか分からないので、2〜3 本のケーブルで送っていると
                # 1 本ずつの比較では合わない (C2000 の USB-C1 100W + USB-C2 60W → C300 DC の USB 入力 150W)。
                # 1 本で合うものを先に探し、なければ本数を増やす
                for n in range(1, min(3, len(outs)) + 1):
                    match = next(((a, c, sum(p["w"] for p in c)) for c in itertools.combinations(outs, n)
                                  if close(sum(p["w"] for p in c), pb["w"])), None)
                    if match:
                        break
                if match:
                    break
            if not match:
                continue
            a, combo, sent = match
            left = pb["w"]
            for i, p in enumerate(combo):  # 受け取った量を、送ったポートの W の比で分ける (合計が受け取りと一致するように)
                recv = left if i == len(combo) - 1 else round(pb["w"] * p["w"] / sent)
                left -= recv
                out.setdefault(a, []).append({"key": p["key"], "dir": "out", "w": p["w"], "peer": b,
                                              "sent": p["w"], "recv": recv})
            out.setdefault(b, []).append({"key": pb["key"], "dir": "in", "w": pb["w"], "peer": a,
                                          "sent": sent, "recv": pb["w"]})
    return out


def net_of_sharing(item: dict, shares: list[dict]) -> None:
    """共有ぶんを、入力・出力の合計から引き、ポートに shared の印を付ける (item を直接書き換える)。"""
    keys = {(x["key"], x["dir"]) for x in shares}
    item["ports"] = [{**p, "shared": True} if (p["key"], p["dir"]) in keys else p for p in item.get("ports") or []]
    for x in shares:
        k = "power_in" if x["dir"] == "in" else "power_out"
        if item.get(k) is not None:
            item[k] = max(0, item[k] - x["w"])
    item["shared"] = shares


def sample_ports(ports: list[dict], shares: list[dict] | None = None) -> dict[str, int]:
    """履歴に書くポート別の W。給電ぶん (shared) は元のポートには書かず、
    送り側・受け側とも「相手が受け取った W」を Transfer に、差をロスに書く。
    送り側の Transfer を自分のポートの W にしないのは、両側の Transfer を同じ量にそろえるため
    (98W 送って 92W 届いたなら、Transfer 92W + ロス 6W)。"""
    out: dict[str, int] = {}
    for p in ports:
        if p["w"] <= 0 or p.get("shared"):
            continue
        out[p["key"]] = out.get(p["key"], 0) + p["w"]
    for x in shares or []:
        recv = x.get("recv", x["w"])
        key = "xfer_in" if x["dir"] == "in" else "xfer_out"
        out[key] = out.get(key, 0) + recv
        loss = x.get("sent", x["w"]) - recv
        if loss > 0:  # 計測の誤差で受け取りのほうが多いときは、ロス 0 とする
            out["xfer_loss"] = out.get("xfer_loss", 0) + loss
    return out


class BleSource(Source):
    """SolixBLE 経由で1台読む。切れたら探し直して繋ぎ直す。"""

    def __init__(self, dev: dict, ble: Ble) -> None:
        self.dev, self.ble, self.id = dev, ble, dev["id"]

    def _set(self, store: Store, state: str, **extra) -> None:
        store.update(self.id, {"state": state, **extra})

    async def run(self, store: Store) -> None:
        import SolixBLE

        cls = getattr(SolixBLE, MODELS[self.dev["model"]]["cls"])
        while True:
            conn = None
            try:
                self._set(store, "searching", detail="")
                found = await self.ble.discover()
                target = next((d for d in found if d.address == self.dev["address"]), None)
                if target is None:
                    self._set(store, "searching",
                              detail="ポタ電が見つかりません。画面を点けて、スマホのAnkerアプリを閉じてください")
                    await asyncio.sleep(10)
                    continue

                self._set(store, "connecting", detail="接続中 (初回は暗号化のやり取りで30秒ほどかかります)")
                conn = cls(target)
                async with self.ble.connect_lock:  # 同時に何台も接続交渉しない
                    ok = await conn.connect()
                if not ok:
                    self._set(store, "connecting", detail="接続に失敗。再試行します")
                    await asyncio.sleep(8)
                    continue

                lost_since = None
                while True:
                    await asyncio.sleep(1)
                    if conn.available:
                        lost_since = None
                        self._set(store, "connected", detail="", **self._read(conn))
                    else:
                        lost_since = lost_since or time.time()
                        self._set(store, "connecting", detail="再接続中…")
                        if time.time() - lost_since > 150:  # ライブラリの自動再接続で戻らなければ探し直す
                            LOG.warning("%s: 150秒戻らないので探し直します", self.dev["name"])
                            break
            except asyncio.CancelledError:
                raise
            except Exception as e:  # BLEは何でも起きる。落とさず続ける
                LOG.exception("BLEエラー (%s): %s", self.dev["name"], e)
                self._set(store, "offline", detail=f"{type(e).__name__}: {e}")
                await asyncio.sleep(10)
            finally:
                if conn is not None:
                    try:
                        await conn.disconnect()
                    except Exception:
                        pass

    @staticmethod
    def _read(conn) -> dict:
        p_in = _get(conn, "power_in")
        if p_in is None:  # 合計入力が無い機種は、AC入力+ソーラーで代用
            p_in = _sum(conn, "ac_power_in", "solar_power_in")
        p_out = _get(conn, "power_out")
        if p_out is None:
            p_out = _sum(conn, "ac_power_out", "dc_power_out")
        status = None
        try:
            status = conn.charging_status.name
        except Exception:
            pass
        values = {}
        for attr, key, _label, _dir in PORT_PROPS:
            v = _get(conn, attr)
            if isinstance(v, (int, float)):
                values[key] = v
        for key in [k for k in values if k.startswith("usb_")]:  # 入力側になっている USB ポートは出力に数えない
            st = _get(conn, "usb_port_" + key[4:])  # usb_c2 → usb_port_c2
            if getattr(st, "value", None) == USB_PORT_INPUT:
                values["in_usb"] = values.get("in_usb", 0) + values.pop(key)
        remaining = _get(conn, "time_remaining")
        return {
            "battery": _get(conn, "battery_percentage"),
            "power_in": p_in,
            "power_out": p_out,
            "ports": build_ports(values, p_in, p_out),
            "solar_in": _get(conn, "solar_power_in"),
            "temperature": _get(conn, "temperature"),
            "charging": derive_charging(status, p_in, p_out),
            "remaining_h": remaining if isinstance(remaining, (int, float)) and remaining > 0 else None,
            "firmware": _get(conn, "software_version"),
        }


# ───────────────────────── クラウド (Anker アカウント + MQTT) ─────────────────────────
def _cnum(d: dict, *names):
    """クラウド値から最初に見つかった数値を返す。MQTT の値は "36" のような文字列で来ることもあるので、数に直す。"""
    for n in names:
        v = d.get(n)
        if isinstance(v, bool):
            continue
        if isinstance(v, str):
            try:
                f = float(v.strip())
            except ValueError:
                continue
            v = int(f) if f == int(f) else f
        if isinstance(v, (int, float)):
            return v
    return None


def _csum(d: dict, *names):
    vals = [v for v in (_cnum(d, n) for n in names) if v is not None]
    return sum(vals) if vals else None


# クラウドの項目名 → ポートのキー (anker-solix-api の PPS 向け MQTT マップ)
USB_PORT_INPUT = 2  # USB ポートの状態: 0 未接続 / 1 出力 / 2 入力 (クラウドの usbc_N_status、SolixBLE の PortStatus とも同じ)

CLOUD_PORTS = [
    (("photovoltaic_power", "dc_input_power_total"), "solar"),
    (("ac_input_power_total",), "ac_in"),
    (("ac_output_power",), "ac_out"),
    (("dc_12v_1_power",), "dc_out"),  # シガーソケット。dc_output_power_total は USB も含む合計なので使わない
    (("usbc_1_power",), "usb_c1"), (("usbc_2_power",), "usb_c2"), (("usbc_3_power",), "usb_c3"), (("usbc_4_power",), "usb_c4"),
    (("usba_1_power",), "usb_a1"), (("usba_2_power",), "usb_a2"),
]


def cloud_read(v: dict) -> dict:
    """クラウド (MQTT) の値を、BLE と同じ形にそろえる。値は文字列で来るので _cnum で数に直す。"""
    values = {}
    usb_in = 0
    dc_total = _cnum(v, "dc_output_power_total")
    for names, key in CLOUD_PORTS:
        w = _cnum(v, *names)
        if w is None:
            continue
        if key.startswith("usb_"):
            st = _cnum(v, names[0].replace("_power", "_status"))
            # usbc_N_power は向きを持たない。状態の項目が無い機種では、DC 出力の合計 (USB を含む) を超えるぶんは
            # 出力ではありえないので入力とみなす
            if st == USB_PORT_INPUT or (st is None and dc_total is not None and w >= 1 and w > dc_total + 1):
                usb_in += w  # 双方向の USB-C が入力側 (別の機器から給電されている)
                continue
        values[key] = w
    if usb_in:
        values["in_usb"] = usb_in
        # dc_input_power_total は USB 入力も含んだ DC 入力の合計なので、そのままソーラーにすると USB 給電が太陽光に見える。
        # photovoltaic_power がある機種 (C300 DC) はそれがソーラー。無い機種は合計から USB 入力を引いた残り
        if _cnum(v, "photovoltaic_power") is None and "solar" in values:
            values["solar"] = max(0, values["solar"] - usb_in)
    # DC 出力の合計 (dc_output_power_total) は USB-C/A もシガーも全部入った値。
    # シガー専用の項目が無い機種 (C2000 Gen 2 など) は「合計 − USB」をシガーとする
    usb_sum = sum(w for k, w in values.items() if k.startswith("usb_"))
    if "dc_out" not in values and dc_total is not None:
        rest = dc_total - usb_sum
        values["dc_out"] = rest if rest >= 1 else 0
    p_in = _csum(v, "ac_input_power_total", "dc_input_power_total")
    if p_in is None:
        p_in = _csum(v, "ac_input_power", "dc_input_power")
    if p_in is None and values:
        p_in = sum(w for k, w in values.items() if PORT_INFO[k][1] == "in") or None
    p_out = _cnum(v, "output_power_total")
    if p_out is None:  # C300 DC などは合計の項目が無い: DC 出力の合計 (+AC 出力があれば足す)
        if dc_total is not None:
            p_out = dc_total + (values.get("ac_out") or 0)
        else:
            outs = [w for k, w in values.items() if PORT_INFO[k][1] == "out"]
            p_out = sum(outs) if outs else None
    status = {0: "IDLE", 1: "DISCHARGING", 2: "CHARGING"}.get(_cnum(v, "battery_status"))
    rem = _cnum(v, "remaining_time_hours")

    def whole(x):  # アプリ側 (Swift) は整数で受けるので、W や % は四捨五入して整数にする
        return None if x is None else int(round(x))

    def onoff(*names):
        x = _cnum(v, *names)
        return None if x is None else bool(x)

    return {
        "battery": whole(_cnum(v, "battery_soc", "main_battery_soc")),
        "power_in": whole(p_in), "power_out": whole(p_out),
        "ports": build_ports({k: whole(w) for k, w in values.items()}, whole(p_in), whole(p_out)),
        "solar_in": whole(values.get("solar")),
        "temperature": whole(_cnum(v, "temperature")),
        "charging": derive_charging(status, whole(p_in), whole(p_out)),
        "remaining_h": rem if rem and rem > 0 else None,
        "firmware": v.get("sw_version"),
        # 出力スイッチの ON/OFF (読み取りのみ)。機種に無いものは None
        "switches": {"ac_out": onoff("ac_output_power_switch"), "dc_out": onoff("dc_output_power_switch")},
    }


class _Restart(Exception):
    """認証情報が変わった / 再接続を頼まれた: 今のセッションを閉じて繋ぎ直す。"""


class CloudHub:
    """Anker アカウントに1回ログインして、登録機器ぶんの MQTT をまとめて受ける。

    ログインの失敗を繰り返すとアカウントが一時ロックされる (エラー100053) ので、
    パスワード違いのときは自動で再試行せず、認証情報が変わるまで待つ。
    """

    def __init__(self, demo: bool = False) -> None:
        self.demo = demo
        self.state = "unconfigured"  # unconfigured / connecting / connected / auth_error / limited / error / missing_lib
        self.detail = ""
        self.devices: list[dict] = []  # アカウントにある機器 [{sn,name,pn,model}]
        self.want: set[str] = set()
        self.data: dict[str, tuple[dict, float, str]] = {}  # sn -> (値, 最後に値が変わった時刻, 指紋)
        self.dbg: dict[str, dict] = {}  # sn -> 取れない原因の手がかり (画面の詳細に出す)
        self.raw: dict[str, dict] = {}  # sn先頭4文字 -> {"at", "values"}: クラウドから届いた生の値 (型もそのまま)
        self._raw_logged: dict[str, float] = {}
        self.recent: dict[str, collections.deque] = {}  # sn先頭4文字 -> 直近に届いた生の値 (コピー用)
        self._last_vals: dict[str, dict] = {}
        self.wake: asyncio.Event | None = None
        self.restart = False
        self.creds = self._load()
        if demo:
            self.creds = {"email": "demo@example.com", "country": "JP"}
            self.state = "connected"
            self.devices = [
                {"sn": "DEMOSN0001", "name": "Anker SOLIX C2000 Gen 2", "pn": "A1783", "model": "c2000g2"},
                {"sn": "DEMOSN0002", "name": "Anker SOLIX C300 DC", "pn": "", "model": "c300dc"},
            ]

    # ── 認証情報 (~/.anker-monitor/cloud.json, 自分だけ読める 0600) ──
    @staticmethod
    def _load() -> dict | None:
        try:
            c = json.loads(CLOUD_FILE.read_text())
            return c if c.get("email") and c.get("password") else None
        except (OSError, ValueError):
            return None

    def save(self, email: str, password: str, country: str) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = CLOUD_FILE.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"email": email, "password": password, "country": country.upper()}, f)
        tmp.replace(CLOUD_FILE)
        self.creds = self._load()
        self.state, self.detail = "connecting", ""
        self.kick()

    def clear(self) -> None:
        try:
            CLOUD_FILE.unlink()
        except OSError:
            pass
        self.creds, self.devices, self.data = None, [], {}
        self.state, self.detail = "unconfigured", ""
        self.kick()

    @staticmethod
    def _mask_raw(st: dict, sn: str) -> dict:
        """生の値のうち、シリアル番号を含むものは伏せる (ログやファイルを貼っても安全なように)。型は変えない。"""
        out = {}
        for k, v in st.items():
            if isinstance(v, str) and sn and sn in v:
                v = v.replace(sn, sn[:4] + "…")
            out[k] = v
        return out

    @staticmethod
    def _write_raw(sn: str, raw: dict) -> None:
        """最新の生の値を ~/.anker-monitor/cloud-raw-XXXX.json に置く (上書き)。"""
        try:
            f = DATA_DIR / f"cloud-raw-{sn[:4]}.json"
            f.write_text(json.dumps({"saved": int(time.time()), "values": raw}, ensure_ascii=False, indent=1,
                                    sort_keys=True, default=repr))
        except OSError:
            pass

    def kick(self) -> None:
        """認証情報の変更・再接続の依頼。接続中のセッションも閉じて繋ぎ直す。"""
        self.restart = True
        if self.wake:
            self.wake.set()

    @staticmethod
    def _mask(email: str) -> str:
        name, _, dom = email.partition("@")
        return (name[:2] + "***@" + dom) if dom else "***"

    def snapshot(self, registered: set[str]) -> dict:
        return {
            "configured": bool(self.creds), "email": self._mask(self.creds["email"]) if self.creds else None,
            "country": (self.creds or {}).get("country"), "state": self.state, "detail": self.detail,
            "devices": [{**d, "model_label": MODELS[d["model"]]["label"] if d.get("model") in MODELS else None,
                         "registered": d["sn"] in registered} for d in self.devices],
        }

    def get(self, sn: str) -> tuple[dict, float, str] | None:
        return self.data.get(sn)

    async def _sleep(self, sec: float) -> None:
        """sec 秒待つ。認証情報が変わったら (kick) すぐ起きる。"""
        assert self.wake is not None
        try:
            await asyncio.wait_for(self.wake.wait(), sec)
        except asyncio.TimeoutError:
            pass
        self.wake.clear()

    async def run(self) -> None:
        self.wake = asyncio.Event()
        if self.demo:
            await asyncio.Event().wait()
        backoff = 30
        while True:
            if not self.creds:
                self.state = "unconfigured"
                await self._sleep(3600)
                continue
            try:
                await self._session()
                backoff = 30
            except asyncio.CancelledError:
                raise
            except _Restart:
                backoff = 30
                continue
            except ImportError:
                self.state, self.detail = "missing_lib", "anker-solix-api が未インストールです。amon cloud-setup を実行してください"
                await self._sleep(3600)
            except Exception as e:
                name = type(e).__name__
                if name in ("InvalidCredentialsError", "AuthorizationError"):
                    self.state = "auth_error"
                    self.detail = "メールアドレスかパスワードが違います (Amazon等でログインしたアカウントは、先にAnkerでパスワードを設定してください)"
                    await self._sleep(86400)  # 新しい認証情報が来るまで再試行しない
                elif name in ("RetryExceeded", "RequestLimitError"):
                    self.state, self.detail = "limited", "Anker 側の回数制限にかかっています。しばらく置いてから再接続します"
                    await self._sleep(3600)
                else:
                    LOG.warning("クラウド接続エラー: %s: %s", name, e)
                    self.state, self.detail = "error", f"{name}: {e}"
                    await self._sleep(backoff)
                    backoff = min(backoff * 2, 900)

    async def _session(self) -> None:
        from aiohttp import ClientSession
        from anker_solix_api.api import AnkerSolixApi
        from anker_solix_api.mqtt_factory import SolixMqttDeviceFactory

        c = self.creds or {}
        self.restart = False
        self.state, self.detail = "connecting", "Anker にログイン中…"
        async with ClientSession() as web:
            api = AnkerSolixApi(c["email"], c["password"], c.get("country") or "JP", web, LOG)
            self.detail = "機器の一覧を取得中…"
            await asyncio.wait_for(api.update_sites(), 90)
            await asyncio.wait_for(api.get_bind_devices(), 90)
            self.devices = []
            for sn, d in api.devices.items():
                pn = d.get("device_pn") or ""
                name = d.get("name") or d.get("alias") or pn or sn
                self.devices.append({"sn": sn, "name": name, "pn": pn,
                                     "model": PN_MODELS.get(pn) or guess_model(name)})
            self.detail = f"機器 {len(self.devices)} 台を確認。MQTT に接続中…"
            LOG.info("機器一覧: %s", [(d["name"], d["pn"], d["sn"][:4] + "…") for d in self.devices])
            sess = await asyncio.wait_for(api.startMqttSession(), 90)
            if not sess:
                raise ConnectionError("MQTT サーバーに接続できません")
            mdevs: dict = {}
            subs: set[str] = set()
            trig: dict[str, float] = {}
            stat: dict[str, float] = {}
            self.state, self.detail = "connected", ""
            LOG.info("MQTT 接続OK。登録機器の SN: %s", [x[:4] + "…" for x in self.want])
            LOG.info("クラウド接続OK (機器 %d 台)", len(self.devices))
            try:
                while True:
                    now = time.time()
                    for sn in list(self.want):
                        dbg = self.dbg.setdefault(sn, {})
                        if sn not in api.devices:
                            dbg["note"] = "このアカウントの機器一覧にありません (別アカウント/共有機器?)"
                            continue
                        md = mdevs.get(sn)
                        if sn not in subs:
                            # 機器からの値を受けるには、機器の「データ」トピックを購読する必要がある
                            # (リアルタイム送信の依頼を出すだけでは、値は届かない)
                            topic = f"{sess.get_topic_prefix(deviceDict=api.devices[sn])}#"
                            err = sess.subscribe(topic) if len(topic) > 1 else "トピック不明"
                            dbg["subscribe"] = "ok" if not err else f"失敗: {err}"
                            LOG.info("購読 %s: %s", topic.replace(sn, sn[:4] + "…"), dbg["subscribe"])
                            if not err:
                                subs.add(sn)
                        if md is None:
                            md = mdevs[sn] = SolixMqttDeviceFactory(api_instance=api, device_sn=sn).create_device()
                        if md is None:
                            dbg["note"] = "この機種は Anker クラウドのリアルタイム取得に未対応です"
                            continue  # この機種は MQTT 未対応
                        if now - trig.get(sn, 0) > CLOUD_TRIGGER_EVERY:
                            trig[sn] = now
                            try:
                                await md.realtime_trigger(timeout=CLOUD_TRIGGER_TIMEOUT)
                                dbg["trigger"] = "ok"
                            except Exception as e:
                                dbg["trigger"] = f"{type(e).__name__}: {e}"[:120]
                                LOG.warning("リアルタイム送信の依頼に失敗 (%s): %s", sn, e)
                        if now - stat.get(sn, 0) > CLOUD_STATUS_EVERY:
                            stat[sn] = now
                            try:
                                r = await md.status_request()
                                dbg["status_req"] = "ok" if r is not None else "送信できず"
                            except Exception as e:
                                dbg["status_req"] = f"{type(e).__name__}: {e}"[:120]
                        st = md.get_status()
                        dbg["keys"] = len(st or {})
                        if not st:
                            first = dbg.setdefault("since", now)
                            if now - first > 60 and not dbg.get("warned"):
                                dbg["warned"] = True
                                LOG.warning("購読後60秒たっても値が届きません (%s…): リアルタイム依頼=%s 状態依頼=%s 購読=%s "
                                            "→ 機器が Wi-Fi でクラウドにつながっていない可能性があります",
                                            sn[:4], dbg.get("trigger"), dbg.get("status_req"), dbg.get("subscribe"))
                            continue  # まだ1件も値が届いていない (空のまま「更新あり」にしない)
                        ts_now = _cnum(st, "msg_timestamp")
                        if ts_now and dbg.get("last_msg") and ts_now != dbg["last_msg"]:
                            d_ = ts_now - dbg["last_msg"]
                            if 0 < d_ < 600:  # 実測の更新間隔 (秒)。なめらかに平均する
                                dbg["interval"] = round(0.8 * dbg.get("interval", d_) + 0.2 * d_, 1)
                        if ts_now:
                            dbg["last_msg"] = ts_now
                        raw = self._mask_raw(st, sn)
                        self.raw[sn[:4]] = {"at": now, "values": raw}
                        k4 = sn[:4]
                        if not self.recent.get(k4) or self.recent[k4][-1].get("msg_ts") != ts_now:
                            self.recent.setdefault(k4, collections.deque(maxlen=RECENT_KEEP)).append(
                                {"at": now, "msg_ts": ts_now, "values": raw})
                            old = self._last_vals.get(k4)
                            if old is not None:  # 値が変わった項目だけログに出す (時刻系は除く)
                                ch = {k: [old.get(k), v] for k, v in raw.items()
                                      if k not in ("msg_timestamp", "last_update", "temperature", "remaining_time_hours")
                                      and not k.endswith(("_power", "_power_total", "_soc_ah")) and old.get(k) != v}
                                if ch:
                                    LOG.info("クラウドの変化 (%s…): %s", k4, json.dumps(ch, ensure_ascii=False, default=repr))
                            self._last_vals[k4] = raw
                        if sn not in self.data or now - self._raw_logged.get(sn, 0) > RAW_LOG_EVERY:
                            self._raw_logged[sn] = now
                            LOG.info("クラウドの生の値 (%s…) %d 項目: %s", sn[:4], len(st),
                                     json.dumps(raw, ensure_ascii=False, sort_keys=True, default=repr))
                            self._write_raw(sn, raw)
                        prev = self.data.get(sn)
                        fp = st.get("msg_timestamp") or json.dumps(st, sort_keys=True, default=str)
                        if prev is None or prev[2] != fp:
                            self.data[sn] = (st, now, str(fp))
                    if not sess.is_connected():
                        raise ConnectionError("MQTT が切れました")
                    await self._sleep(CLOUD_POLL)
                    if self.restart:
                        raise _Restart()
            finally:
                try:
                    sess.cleanup()
                except Exception:
                    pass


class CloudSource(Source):
    """CloudHub の値を、機器1台ぶんの「クラウド」チャンネルへ流す。"""

    def __init__(self, dev: dict, hub: CloudHub) -> None:
        self.dev, self.hub, self.id = dev, hub, dev["id"]

    def _set(self, store: Store, state: str, **extra) -> None:
        store.update(self.id, {"state": state, **extra}, channel="cloud")

    def _why(self, sn: str) -> str:
        d = self.hub.dbg.get(sn) or {}
        bits = []
        if d.get("note"):
            bits.append(d["note"])
        if d.get("subscribe"):
            bits.append("データ購読: " + d["subscribe"])
        if d.get("trigger"):
            bits.append("リアルタイム送信の依頼: " + d["trigger"])
        if d.get("status_req"):
            bits.append("状態の依頼: " + d["status_req"])
        if "keys" in d:
            bits.append(f"届いた項目: {d['keys']} 件")
        return " [" + " / ".join(bits) + "]" if bits else ""

    async def run(self, store: Store) -> None:
        sn = self.dev["cloud_sn"]
        self.hub.want.add(sn)
        try:
            while True:
                await asyncio.sleep(CLOUD_POLL)
                got = self.hub.get(sn)
                if self.hub.state != "connected":
                    self._set(store, "connecting" if self.hub.state == "connecting" else "offline",
                              detail=self.hub.detail or "クラウド未接続")
                elif got is None:
                    self._set(store, "connecting",
                              detail="Anker にはログイン済み。機器の最初の値を待っています" + self._why(sn))
                elif time.time() - got[1] > CLOUD_STALE:
                    self._set(store, "offline",
                              detail="クラウドからの更新が止まっています (機器がWi-Fiに繋がっているか確認)" + self._why(sn))
                else:
                    self._set(store, "connected", detail="", event_at=int(got[1]), **cloud_read(got[0]))
        finally:
            self.hub.want.discard(sn)


class DemoSource(Source):
    """画面確認用のダミー。機器ごとに少しずつ違う動きをする。"""

    def __init__(self, dev: dict, phase: float = 0.0) -> None:
        self.dev, self.id, self.phase = dev, dev["id"], phase

    async def run(self, store: Store) -> None:
        big = self.dev["model"] == "c2000g2"
        cap = 2048 if big else 288
        scale = cap / 288 if big else 1
        pct, t = 55.0 + self.phase * 10, int(self.phase * 40)
        store.update(self.id, {"state": "connecting", "detail": "接続中…"})
        await asyncio.sleep(1.5)
        while True:
            t += 1
            charging = math.sin(t / 40 + self.phase) > -0.1
            solar = int(max(0, (170 + 40 * math.sin(t / 7)) * scale + random.randint(-8, 8))) if charging else 0
            ac_in = int(60 * scale) if charging and t % 90 > 45 else 0
            c1 = int(max(0, 35 + 20 * math.sin(t / 5) + random.randint(-4, 4)))
            c2 = int(max(0, 18 + 10 * math.sin(t / 9)))
            dc = int(max(0, (25 + 8 * math.sin(t / 11)) * (1.5 if big else 1)))
            ac_out = int(150 * scale * 0.5) if big and t % 120 > 60 else 0
            p_in, p_out = solar + ac_in, c1 + c2 + dc + ac_out
            ports = [p for p in (
                ("solar", solar), ("ac_in", ac_in), ("usb_c1", c1), ("usb_c2", c2), ("dc_out", dc), ("ac_out", ac_out)
            ) if p[1] > 0]
            pct = min(100.0, max(3.0, pct + (p_in - p_out) / cap * 100 / 3600 * 25))
            store.update(self.id, {
                "state": "connected", "battery": int(pct), "power_in": p_in, "power_out": p_out,
                "ports": [{"key": k, "label": PORT_INFO[k][0], "dir": PORT_INFO[k][1], "w": w} for k, w in ports],
                "solar_in": solar, "temperature": 27 + int(3 * math.sin(t / 30)),
                "charging": derive_charging(None, p_in, p_out),
                "remaining_h": round(abs(pct - (100 if p_in > p_out else 0)) / 100 * cap / max(1, abs(p_in - p_out)), 1),
                "firmware": "demo", "detail": "",
                "switches": {"ac_out": (t % 120 > 60) if big else None, "dc_out": True},
            })
            await asyncio.sleep(1)


def seed_demo_history(db: HistoryDB, devs: list[dict]) -> None:
    """デモ用に3日分の履歴を作る (履歴タブの確認用)。入力0Wは記録しない。"""
    if db.count() > 0:
        return
    now = int(time.time())
    for d in devs:
        big = d["model"] == "c2000g2"
        cap = 2048 if big else 288
        pct, samples, ports = 70.0, [], []
        for ts in range(now - 3 * 86400, now - 120, SAMPLE_INTERVAL):
            hour = ((ts + 9 * 3600) % 86400) / 3600  # 日本時間の時刻
            sun = max(0.0, math.sin((hour - 6) / 12 * math.pi))
            solar = int(sun * (cap * 0.8) * (0.7 + 0.3 * random.random()))
            ac_in = int(cap * 0.15) if 1 <= hour <= 4 else 0
            c1 = int((cap * 0.06) * (0.4 + random.random())) if 8 <= hour <= 23 else 0
            dc = int((cap * 0.05) * (0.5 + random.random()))
            ac_out = int(cap * 0.25 * (0.5 + random.random())) if big and 18 <= hour <= 22 else 0
            p_in, p_out = solar + ac_in, c1 + dc + ac_out
            pct = min(100.0, max(4.0, pct + (p_in - p_out) / cap * 100 / (3600 / SAMPLE_INTERVAL)))
            samples.append((ts, d["id"], int(pct), p_in or None, p_out, solar, 26,
                            "CHARGING" if p_in > p_out else "DISCHARGING"))
            for key, w in (("solar", solar), ("ac_in", ac_in), ("usb_c1", c1), ("dc_out", dc), ("ac_out", ac_out)):
                if w > 0:
                    ports.append((ts, d["id"], key, w))
        db.insert(samples, ports)


# ───────────────────────── 全体の管理 ─────────────────────────
class Manager:
    def __init__(self, loop: asyncio.AbstractEventLoop, demo: bool) -> None:
        self.loop, self.demo = loop, demo
        self.store = Store()
        self.registry = Registry(persist=not demo)
        self.db = HistoryDB(DATA_DIR / ("history-demo.db" if demo else "history.db"))
        self.ble: Ble | FakeBle = FakeBle() if demo else Ble()
        self.cloud = CloudHub(demo)
        self.tasks: dict[str, asyncio.Task] = {}
        self.cloud_task: asyncio.Task | None = None
        if demo and not self.registry.devices:
            for addr, name in (("DEMO-A", "Anker SOLIX C300 DC"), ("DEMO-B", "Anker SOLIX C2000 Gen 2")):
                self.registry.add(self._new_device(addr, name, guess_model(name) or "c300dc"))
            seed_demo_history(self.db, self.registry.list())

    @staticmethod
    def _new_device(address: str | None, name: str, model: str, cloud_sn: str | None = None) -> dict:
        dev = {"id": device_id(address) if address else cloud_device_id(cloud_sn or ""),
               "name": name, "model": model, "added": int(time.time())}
        if address:
            dev["address"] = address
        if cloud_sn:
            dev["cloud_sn"] = cloud_sn
        dev["source"] = dev_source(dev)
        return dev

    def start(self) -> None:
        self.cloud_task = self.loop.create_task(self.cloud.run())
        for i, d in enumerate(self.registry.list()):
            self._spawn(d, i)

    async def _run_device(self, d: dict, index: int) -> None:
        """機器1台ぶん: BLE とクラウドのデータ源を並べて動かす (止めるときは両方止める)。"""
        if self.demo:
            subs: list[Source] = [DemoSource(d, phase=index * 1.7)]
        else:
            subs = []
            if d.get("address"):
                subs.append(BleSource(d, self.ble))  # type: ignore[arg-type]
            if d.get("cloud_sn"):
                subs.append(CloudSource(d, self.cloud))
        tasks = [asyncio.create_task(x.run(self.store)) for x in subs]
        try:
            await asyncio.gather(*tasks)
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)  # Bluetoothを切るまで待つ

    def _spawn(self, d: dict, index: int = 0) -> None:
        self.db.set_meta(d["id"], d["name"], d["model"], {k: d[k] for k in ("address", "cloud_sn", "source", "added") if k in d})
        self.tasks[d["id"]] = self.loop.create_task(self._run_device(d, index))

    async def _stop_task(self, dev_id: str) -> None:
        task = self.tasks.pop(dev_id, None)
        if task:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def add(self, address: str | None, name: str, model: str, cloud_sn: str | None = None) -> tuple[bool, str]:
        if model not in MODELS:
            return False, "未対応の機種です"
        if not address and not cloud_sn:
            return False, "Bluetooth の機器かクラウドの機器を指定してください"
        dev = self._new_device(address, name, model, cloud_sn)
        if not self.registry.add(dev):
            return False, "すでに登録されています"
        self._spawn(dev, len(self.tasks))
        return True, dev["id"]

    async def link_cloud(self, dev_id: str, cloud_sn: str | None) -> bool:
        """登録済みの機器にクラウドの機器をひも付ける (None で外す)。BLE もクラウドも無くなる指定は不可。"""
        d = self.registry.get(dev_id)
        if not d or (not cloud_sn and not d.get("address")):
            return False
        if cloud_sn and any(o.get("cloud_sn") == cloud_sn and o["id"] != dev_id for o in self.registry.list()):
            return False
        await self._stop_task(dev_id)
        self.store.drop(dev_id)
        self.registry.update(dev_id, cloud_sn=cloud_sn)
        self._spawn(self.registry.get(dev_id) or d, len(self.tasks))
        return True

    async def remove(self, dev_id: str) -> bool:
        await self._stop_task(dev_id)
        self.store.drop(dev_id)
        return self.registry.remove(dev_id)

    async def sampler(self) -> None:
        """BASE_TICK ごとに現在値を拾い、SAMPLE_INTERVAL ぶんを平均して履歴に1行書く
        (間引いても、電力量 (Wh) が狂わないように、捨てずに平均する)。"""
        buf: dict[str, list] = {}
        last_flush = time.time()
        last_savings = 0.0
        thinned_day = ""
        while True:
            await asyncio.sleep(BASE_TICK)
            now = int(time.time())
            shared = self.sharing()  # 機器どうしの電力共有ぶんは、入出力にも履歴にも数えない
            for d in self.registry.list():
                v = dict(self.store.get(d["id"]))
                if v.get("state") != "connected" or v.get("battery") is None:
                    continue
                if d["id"] in shared:
                    net_of_sharing(v, shared[d["id"]])
                buf.setdefault(d["id"], []).append({
                    "pct": v["battery"], "pin": v.get("power_in") or 0, "pout": v.get("power_out") or 0,
                    "temp": v.get("temperature"), "chg": v.get("charging"),
                    "ports": sample_ports(v.get("ports") or [], shared.get(d["id"]))})
            if time.time() - last_flush >= SAMPLE_INTERVAL - 1 and buf:
                samples, ports = [], []
                for dev, xs in buf.items():
                    n = len(xs)
                    pin = round(sum(x["pin"] for x in xs) / n)
                    samples.append((now, dev, xs[-1]["pct"], pin or None, round(sum(x["pout"] for x in xs) / n), 0,
                                    xs[-1]["temp"], xs[-1]["chg"]))
                    keys = {k for x in xs for k in x["ports"]}
                    for k in keys:
                        w = round(sum(x["ports"].get(k, 0) for x in xs) / n)
                        if w > 0:
                            ports.append((now, dev, k, w))
                self.db.insert(samples, ports)
                buf.clear()
                last_flush = time.time()
            if time.time() - last_savings > 600:  # 節電記録 (日ごと) を更新
                last_savings = time.time()
                try:
                    loop = asyncio.get_running_loop()
                    await loop.run_in_executor(None, self.db.refresh_savings, RAW_KEEP_DAYS + 1)
                    today = time.strftime("%Y-%m-%d")
                    if today != thinned_day:  # 日が変わったら1回だけ: 古い日を確定して間引く
                        r = await loop.run_in_executor(None, self.db.thin_old)
                        thinned_day = today
                        LOG.info("古い履歴を %d 秒に間引きました: %s 件 → %s 件", THIN_INTERVAL, r["before"], r["after"])
                except Exception as e:  # noqa: BLE001
                    LOG.warning("節電記録の更新・間引きに失敗: %s", e)

    def sharing(self) -> dict[str, list[dict]]:
        return find_sharing({d["id"]: self.store.get(d["id"]) for d in self.registry.list()})

    def snapshot(self) -> dict:
        devs = []
        shared = self.sharing()
        for d in self.registry.list():
            m = MODELS.get(d["model"], {})
            item = {
                "id": d["id"], "name": d["name"], "model": d["model"], "model_label": m.get("label", d["model"]),
                "icon": m.get("icon", "box"), "experimental": bool(m.get("experimental")),
                "source": d.get("source") or dev_source(d), "address": d.get("address"),
                "cloud_sn": d.get("cloud_sn"), "via": None, "channels": {},
                "state": "searching", "battery": None, "power_in": None, "power_out": None,
                "ports": [], "charging": "UNKNOWN", "detail": "",
            }
            item.update(self.store.get(d["id"]))
            shares = shared.get(d["id"])
            if shares:
                net_of_sharing(item, shares)
                for x in shares:
                    pd = self.registry.get(x["peer"]) if hasattr(self.registry, "get") else None
                    x["peer_name"] = (pd or {}).get("name") or x["peer"]
            cap, b_, rh_ = MODEL_WH.get(d["model"]), item.get("battery"), item.get("remaining_h")
            # 給電ぶんを引いて 0W になっただけの機器は待機ではない (残り時間は給電込みの見積もりなので、待機電力に見えてしまう)
            if cap and b_ is not None and rh_ and not shares and (item.get("power_in") or 0) == 0 and (item.get("power_out") or 0) == 0:
                item["standby_w"] = round(b_ / 100 * cap / rh_, 1)  # 入出力 0W のとき: 残量 ÷ 機器の残り時間 ≒ 待機消費 (目安)
            devs.append(item)
        return {"ok": True, "now": time.time(), "devices": devs}

    async def scan(self) -> list[dict]:
        found = await self.ble.discover(max_age=0)
        registered = {d.get("address") for d in self.registry.list()}
        out = []
        for d in found:
            model = guess_model(d.name)
            out.append({
                "address": d.address, "name": d.name or "(名前なし)", "model": model,
                "model_label": MODELS[model]["label"] if model else None,
                "experimental": bool(model and MODELS[model].get("experimental")),
                "registered": d.address in registered,
                "uuid_match": bool(getattr(self.ble, "meta", {}).get(d.address, {}).get("uuid", True)),
            })
        return out

    async def scan_all(self) -> list[dict]:
        return await self.ble.scan_all()

    def cloud_snapshot(self) -> dict:
        return self.cloud.snapshot({d["cloud_sn"] for d in self.registry.list() if d.get("cloud_sn")})


# ───────────────────────── HTTP ─────────────────────────
def nice_bucket(span: float) -> int:
    """グラフ用に、およそ600点になる集計間隔 (秒)。"""
    raw = max(SAMPLE_INTERVAL, span / 600)
    for b in (10, 20, 30, 60, 120, 300, 600, 900, 1800, 3600):
        if raw <= b:
            return b
    return 3600


def make_handler(mgr: Manager, loop: asyncio.AbstractEventLoop, loopback: bool):
    def run_async(coro, timeout: float = 60):
        return asyncio.run_coroutine_threadsafe(coro, loop).result(timeout)

    def span(q: dict) -> tuple[float, float]:
        t1 = float(q.get("to", time.time()))
        t0 = float(q["from"]) if "from" in q else t1 - float(q.get("hours", 1)) * 3600
        return t0, t1

    class Handler(BaseHTTPRequestHandler):
        def _send(self, code: int, body: bytes, ctype: str = "application/json") -> None:
            self.send_response(code)
            self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text/") else ""))
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, obj, code: int = 200) -> None:
            self._send(code, json.dumps(obj, ensure_ascii=False).encode())

        def _guard(self, write: bool) -> bool:
            """他のサイトのページから叩かれないようにする (localhost のみ公開のとき)。
            LAN に公開しているときは、このMac以外からは「見るだけ」: 読み取り用のパスだけ許し、
            ログイン情報・機器の操作・履歴の削除・設定の変更はできない。"""
            ip = self.client_address[0]
            if ip not in ("127.0.0.1", "::1", "::ffff:127.0.0.1") and not loopback:
                if not self._token_ok():
                    return False
                path = urlparse(self.path).path
                if write or path not in LAN_READ_PATHS:
                    self._json({"error": "このMac以外からは見るだけです"}, 403)
                    return False
            host = self.headers.get("Host", "")
            if loopback and host.split(":")[0] not in ("localhost", "127.0.0.1"):
                self._json({"error": "forbidden host"}, 403)
                return False
            origin = self.headers.get("Origin")
            if write and origin and urlparse(origin).netloc != host:
                self._json({"error": "forbidden origin"}, 403)
                return False
            return True

        def _token_ok(self) -> bool:
            """トークンが設定されていれば、Authorization: Bearer / Cookie / ?token= のどれかで一致を求める。
            ダッシュボードの fetch は相対パスでヘッダーを付けられないので、ブラウザと WebView は Cookie で通す。
            ?token= で来たら Cookie に移して、トークンを外した URL へ飛ばし直す (アドレス欄と履歴に残さない)。"""
            want = api_token()
            if not want:
                return True
            u = urlparse(self.path)
            q = parse_qs(u.query)
            auth = self.headers.get("Authorization", "")
            got = auth[7:].strip() if auth.lower().startswith("bearer ") else None
            if got is None:
                for part in (self.headers.get("Cookie") or "").split(";"):
                    k, _, v = part.strip().partition("=")
                    if k == TOKEN_COOKIE:
                        got = v
            from_query = q.get("token", [None])[0]
            if from_query is not None:
                got = from_query
            if got is not None and hmac.compare_digest(got.encode(), want.encode()):
                if from_query is not None and self.command == "GET":
                    rest = urlencode([(k, v) for k, vs in q.items() if k != "token" for v in vs])
                    self.send_response(303)
                    self.send_header("Set-Cookie", f"{TOKEN_COOKIE}={want}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000")
                    self.send_header("Location", u.path + ("?" + rest if rest else ""))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return False
                return True
            if u.path in PAGE_PATHS and not (u.path == "/history" and "dev" in q):
                wrong = "<p style='color:#c33'>トークンが違います</p>" if got is not None else ""
                page = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                        "<title>Anker Monitor</title><body style='font-family:system-ui;padding:24px;max-width:420px'>"
                        "<h3>Anker Monitor</h3><p>このMac以外から見るには、トークンが必要です。"
                        "記録している Mac で <code>amon token</code> を実行すると表示されます。</p>" + wrong +
                        "<form method=get><input name=token type=password autocomplete=current-password "
                        "style='width:100%;padding:8px;font-size:16px' placeholder='トークン' autofocus>"
                        "<p><button style='padding:8px 16px;font-size:16px'>開く</button></p></form>")
                self._send(401, page.encode(), "text/html")
            else:
                self._json({"error": "トークンが必要です (Authorization: Bearer <token>)"}, 401)
            return False

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 10_000:
                return {}
            try:
                return json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return {}

        # ── GET ──
        def do_GET(self) -> None:  # noqa: N802
            if not self._guard(False):
                return
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            try:
                if u.path in PAGE_PATHS and not (u.path == "/history" and "dev" in parse_qs(u.query)):
                    self._send(200, HTML_FILE.read_bytes(), "text/html")
                elif u.path == "/status":
                    self._json(mgr.snapshot())
                elif u.path in ("/history", "/api/history"):  # /history?dev=… は旧名 (ページの /history と区別)
                    t0, t1 = span(q)
                    b = nice_bucket(t1 - t0)
                    self._json({"dev": q.get("dev"), "from": t0, "to": t1, "bucket": b,
                                "rows": mgr.db.series(q.get("dev", ""), t0, t1, b)})
                elif u.path == "/ports":
                    t0, t1 = span(q)
                    b = nice_bucket(t1 - t0)
                    dev = q.get("dev", "")
                    series = mgr.db.port_series(dev, t0, t1, b)
                    self._json({"dev": dev, "from": t0, "to": t1, "bucket": b,
                                "series": series, "totals": mgr.db.port_totals(dev, t0, t1),
                                "info": {k: {"label": v[0], "dir": v[1]} for k, v in PORT_INFO.items()}})
                elif u.path == "/summary":
                    t0, t1 = span(q if "from" in q else {**q, "hours": 24})
                    self._json(mgr.db.summary(q.get("dev", ""), t0, t1))
                elif u.path == "/api/daily":
                    t0, t1 = span(q if "from" in q else {**q, "hours": 24 * 31})
                    mgr.db.refresh_savings(3)  # 直近 3 日は、いつでも最新にしてから返す
                    off = time.localtime().tm_gmtoff or 0
                    d0, d1 = (time.strftime("%Y-%m-%d", time.gmtime(t + off)) for t in (t0, t1 + 86400))
                    self._json({"rows": mgr.db.savings_rows(t0, t1, q.get("dev") or None), "settings": load_settings(),
                                "weather": mgr.db.weather_rows(d0, d1), "tz_offset": off})
                elif u.path == "/api/raw":  # 解析用 (amon get): 間引かない log と、日ごとの集計
                    t0, t1 = span(q if "from" in q else {**q, "hours": float(q.get("hours", 24))})
                    out = mgr.db.raw(q.get("dev") or None, t0, t1, min(int(q.get("limit", 20000)), 200000))
                    off = time.localtime().tm_gmtoff or 0
                    d0, d1 = (time.strftime("%Y-%m-%d", time.gmtime(t + off)) for t in (t0, t1 + 86400))
                    out.update({"from": t0, "to": t1, "tz_offset": off,
                                "daily": mgr.db.savings_rows(t0, t1, q.get("dev") or None),
                                "weather": mgr.db.weather_rows(d0, d1)})
                    self._json(out)
                elif u.path == "/api/geocode":  # 天気の場所を地名で探す (設定はこのMacからだけなので、ここも同じ)
                    self._json({"results": geocode(q.get("q", ""))})
                elif u.path == "/api/settings":
                    self._json(load_settings())
                elif u.path == "/api/history-devices":
                    self._json(mgr.db.devices())
                elif u.path == "/api/storage":
                    self._json(mgr.db.storage())
                elif u.path == "/api/cloud/raw":
                    self._json({"raw": mgr.cloud.raw,
                                "interval_sec": {k[:4]: v.get("interval") for k, v in mgr.cloud.dbg.items()}})
                elif u.path == "/api/cloud/recent":
                    n = int(q.get("n", 30))
                    self._json({k: list(v)[-n:] for k, v in mgr.cloud.recent.items()})
                elif u.path == "/api/cloud":
                    self._json(mgr.cloud_snapshot())
                elif u.path == "/api/scan-all":
                    self._json({"results": run_async(mgr.scan_all(), 60)})
                elif u.path == "/api/scan":
                    self._json({"results": run_async(mgr.scan(), 60),
                                "models": [{"key": k, "label": m["label"], "experimental": bool(m.get("experimental"))}
                                           for k, m in MODELS.items()]})
                else:
                    self._send(404, b'{"error":"not found"}')
            except (KeyError, ValueError) as e:
                self._json({"error": f"bad request: {e}"}, 400)
            except Exception as e:
                LOG.exception("GET %s", self.path)
                self._json({"error": f"{type(e).__name__}: {e}"}, 500)

        # ── POST / DELETE ──
        def do_POST(self) -> None:  # noqa: N802
            if not self._guard(True):
                return
            u, b = urlparse(self.path), self._body()
            try:
                if u.path == "/api/devices":
                    address, name, model = b.get("address"), (b.get("name") or "").strip(), b.get("model")
                    cloud_sn = b.get("cloud_sn")
                    if not (address or cloud_sn) or not name:
                        return self._json({"error": "address (または cloud_sn) と name が必要です"}, 400)
                    ok, res = run_async(mgr.add(address, name[:60], model or "", cloud_sn), 10)
                    self._json({"ok": ok, "id" if ok else "error": res}, 200 if ok else 400)
                elif u.path == "/api/cloud":
                    if not loopback:
                        return self._json({"error": "クラウドの認証情報はこのMacの画面からだけ設定できます"}, 403)
                    email, pw = (b.get("email") or "").strip(), b.get("password") or ""
                    if not b.get("email") and not b.get("password") and mgr.cloud.creds:
                        mgr.cloud.state = "connecting"  # 保存済みの認証情報で再接続
                        mgr.cloud.kick()
                    elif "@" not in email or not pw:
                        return self._json({"error": "メールアドレスとパスワードが必要です"}, 400)
                    else:
                        mgr.cloud.save(email, pw, (b.get("country") or "JP").strip()[:2])
                    self._json({"ok": True})
                elif u.path == "/api/settings":
                    self._json(save_settings(b))
                elif u.path == "/api/history/compact":
                    if not loopback:
                        return self._json({"error": "間引きはこのMacの画面からだけできます"}, 403)
                    days = b.get("older_than_days")
                    before = int(time.time() - float(days) * 86400) if days not in (None, "") else None
                    r = mgr.db.compact(int(b.get("interval") or load_settings()["sample_interval"]), before)
                    self._json({"ok": True, **r, "storage": mgr.db.storage()})
                elif u.path == "/api/history/clear":
                    if not loopback:
                        return self._json({"error": "履歴の削除はこのMacの画面からだけできます"}, 403)
                    dev = b.get("dev") or None
                    days = b.get("older_than_days")
                    before = int(time.time() - float(days) * 86400) if days not in (None, "") else None
                    n = mgr.db.delete(dev, before)
                    self._json({"ok": True, "deleted": n, "storage": mgr.db.storage()})
                elif u.path.startswith("/api/devices/") and u.path.endswith("/cloud"):
                    dev_id = unquote(u.path.split("/")[3])
                    ok = run_async(mgr.link_cloud(dev_id, b.get("cloud_sn") or None), 15)
                    self._json({"ok": ok} if ok else {"error": "ひも付けできませんでした (すでに別の機器に使われている等)"}, 200 if ok else 400)
                elif u.path.startswith("/api/devices/") and u.path.endswith("/rename"):
                    dev_id = unquote(u.path.split("/")[3])
                    name = (b.get("name") or "").strip()[:60]
                    if not name or not mgr.registry.rename(dev_id, name):
                        return self._json({"error": "名前を変えられませんでした"}, 400)
                    mgr.db.set_meta(dev_id, name, (mgr.registry.get(dev_id) or {}).get("model", ""))
                    self._json({"ok": True})
                else:
                    self._send(404, b'{"error":"not found"}')
            except Exception as e:
                LOG.exception("POST %s", self.path)
                self._json({"error": f"{type(e).__name__}: {e}"}, 500)

        def do_DELETE(self) -> None:  # noqa: N802
            if not self._guard(True):
                return
            u = urlparse(self.path)
            try:
                if u.path == "/api/cloud":
                    if not loopback:
                        return self._json({"error": "このMacの画面からだけ操作できます"}, 403)
                    mgr.cloud.clear()
                    self._json({"ok": True})
                elif u.path.startswith("/api/devices/"):
                    ok = run_async(mgr.remove(unquote(u.path.split("/")[3])), 15)
                    self._json({"ok": ok}, 200 if ok else 404)
                else:
                    self._send(404, b'{"error":"not found"}')
            except Exception as e:
                LOG.exception("DELETE %s", self.path)
                self._json({"error": f"{type(e).__name__}: {e}"}, 500)

        def log_message(self, *_args) -> None:  # アクセスログは出さない
            pass

    return Handler


LAN_READ_PATHS = {"/", "/index.html", "/history", "/status", "/ports", "/summary", "/api/daily", "/api/history",
                  "/api/history-devices", "/api/storage", "/api/settings", "/api/raw", "/favicon.ico"}


# ───────────────────────── 起動 ─────────────────────────
async def amain(args) -> None:
    loop = asyncio.get_running_loop()
    apply_settings(load_settings())
    mgr = Manager(loop, args.demo)
    threading.Thread(target=lambda: (mgr.db.refresh_savings(), mgr.db.thin_old()), daemon=True).start()  # 節電記録を全期間ぶん作ってから間引く (初回だけ重い)
    threading.Thread(target=weather_loop, args=(mgr.db,), daemon=True).start()
    if args.host is None:  # 指定が無ければ、LAN 公開のスイッチ (~/.anker-monitor/lan) で決める
        args.host = "0.0.0.0" if LAN_FLAG.exists() else "127.0.0.1"
    loopback = args.host in ("127.0.0.1", "localhost")
    server = ThreadingHTTPServer((args.host, args.port), make_handler(mgr, loop, loopback))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    LOG.info("http://%s:%d で待ち受け中%s", "localhost" if loopback else args.host, args.port,
             " (デモ)" if args.demo else "")

    mgr.start()
    sampler = loop.create_task(mgr.sampler())

    def stop() -> None:
        sampler.cancel()
        if mgr.cloud_task:
            mgr.cloud_task.cancel()
        for t in list(mgr.tasks.values()):
            t.cancel()

    # アプリ終了 (SIGTERM) や Ctrl+C のとき、Bluetoothを切ってから終わる
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop)
    try:
        await asyncio.gather(sampler, *([mgr.cloud_task] if mgr.cloud_task else []), *mgr.tasks.values(),
                             return_exceptions=True)
        await asyncio.gather(*mgr.tasks.values(), return_exceptions=True)  # 実行中に登録した分の後片付けも待つ
    finally:
        server.shutdown()


def main() -> None:
    ap = argparse.ArgumentParser(description="Anker SOLIX モニター (バックエンド)")
    ap.add_argument("--port", type=int, default=19877)
    ap.add_argument("--host", default=None, help="他のMacから見るなら 0.0.0.0")
    ap.add_argument("--demo", action="store_true", help="ダミー機器・ダミー履歴で起動")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--scan-all", action="store_true", help="周りのBluetooth機器を全部表示して終了 (機種が見つからないとき用)")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    if args.scan_all:
        print("Bluetooth を10秒スキャンします。ポタ電の画面を点けておいてください…")
        res = asyncio.run(Ble().scan_all(10))
        hit = [d for d in res if d["uuid_match"] or d["anker_like"]]
        print(f"\n見つかった機器 {len(res)} 件 / Anker らしいもの {len(hit)} 件\n")
        for d in res[:40]:
            mark = "★" if (d["uuid_match"] or d["anker_like"]) else " "
            print(f"{mark} {d['name'] or '(名前なし)':32} {d['address']}  RSSI {d['rssi']}  "
                  f"UUID一致={'はい' if d['uuid_match'] else 'いいえ'}  services={d['service_uuids']}")
        if not hit:
            print("\nAnker らしい機器が見つかりませんでした。画面点灯・スマホのAnkerアプリ終了・Bluetooth許可を確認してください。")
        return
    try:
        asyncio.run(amain(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
