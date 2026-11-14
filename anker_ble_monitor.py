#!/usr/bin/env python3
"""Anker SOLIX C300 DC を Bluetooth で直接監視する。

使い方:
    python anker_ble_monitor.py            # 近くのAnker機器を探して接続
    python anker_ble_monitor.py --scan     # 見つかった機器の一覧だけ表示
    python anker_ble_monitor.py --name C300  # 名前に C300 を含む機器に接続
    python anker_ble_monitor.py --debug    # 詳細ログ（うまくいかない時用）

終了は Ctrl+C。
"""

import argparse
import asyncio
import logging
from datetime import datetime

import SolixBLE
from SolixBLE import C300DC


def pick(devices, name):
    """接続先を1台選ぶ。"""
    if name:
        hits = [d for d in devices if name.lower() in (d.name or "").lower()]
        if not hits:
            raise SystemExit(f"名前に '{name}' を含む機器が見つかりませんでした。")
        return hits[0]
    if len(devices) == 1:
        return devices[0]
    for i, d in enumerate(devices):
        print(f"  [{i}] {d.name}  ({d.address})")
    return devices[int(input("どれに接続しますか？ 番号: "))]


def line(dev: C300DC) -> str:
    """1行分の表示を作る。"""
    now = datetime.now().strftime("%H:%M:%S")
    if not dev.available:
        return f"{now}  接続待ち…"
    return (
        f"{now}  "
        f"🔋 {dev.battery_percentage:>3}%  "
        f"入力 {dev.power_in:>4}W (ソーラー {dev.solar_power_in}W)  "
        f"出力 {dev.power_out:>4}W  "
        f"残り {dev.time_remaining:.1f}h  "
        f"{dev.temperature}℃  "
        f"{dev.charging_status.name}"
    )


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", action="store_true", help="一覧表示だけして終了")
    ap.add_argument("--name", help="接続する機器名の一部")
    ap.add_argument("--timeout", type=int, default=10, help="スキャン秒数")
    ap.add_argument("--debug", action="store_true")
    args = ap.parse_args()

    if args.debug:
        logging.basicConfig(level=logging.DEBUG)

    print(f"Anker機器を {args.timeout} 秒スキャン中…")
    devices = await SolixBLE.discover_devices(timeout=args.timeout)
    if not devices:
        raise SystemExit(
            "見つかりませんでした。次を確認してください:\n"
            " ・ポタ電の電源が入っていて、Bluetoothが有効か（電源ボタンを一度押す）\n"
            " ・スマホのAnkerアプリを終了しているか（同時接続はできません）\n"
            " ・ターミナルにBluetoothの権限があるか\n"
            "   （システム設定 > プライバシーとセキュリティ > Bluetooth）"
        )

    if args.scan:
        for d in devices:
            print(f"  {d.name}  ({d.address})")
        return

    target = pick(devices, args.name)
    print(f"{target.name} に接続中…（初回は暗号化のやり取りで少し時間がかかります）")

    dev = C300DC(target)
    # 値が変わるたびに呼ばれる
    dev.add_callback(lambda: print(line(dev)))

    if not await dev.connect():
        raise SystemExit("接続に失敗しました。--debug を付けて再実行してみてください。")

    try:
        # 変化がなくても30秒ごとに現在値を表示
        while True:
            await asyncio.sleep(30)
            print(line(dev))
    finally:
        await dev.disconnect()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n終了しました。")
