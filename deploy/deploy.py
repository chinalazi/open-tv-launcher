#!/usr/bin/env python3
"""把 OpenTV Launcher 装到电视并占住 Home 键（不依赖 adb 二进制 / Android SDK）。

依赖：pip install -r deploy/requirements.txt

用法（Windows / macOS 均可，先确认电视已开 USB 调试、与电脑同一 Wi-Fi）：
    python deploy/deploy.py --host 192.168.1.50 --probe
    python deploy/deploy.py --host 192.168.1.50 --install app-release.apk
    python deploy/deploy.py --host 192.168.1.50 --set-home
    python deploy/deploy.py --host 192.168.1.50 --disable-stock com.mitv.tvhome
    python deploy/deploy.py --host 192.168.1.50 --restore-stock com.mitv.tvhome
    python deploy/deploy.py --host 192.168.1.50 --install app-release.apk --set-home

注意：务必先装好新桌面、再停用原桌面，否则按 Home 可能落到工厂测试应用。
"""
import argparse
import os
import sys

PACKAGE = "com.openlauncher.tv"
REMOTE_APK = "/data/local/tmp/opentv-launcher.apk"
KEY_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".adbkey")
KEY_PATH = os.path.join(KEY_DIR, "adbkey")

COMMON_STOCK_LAUNCHERS = (
    "com.mitv.tvhome",              # 小米国行 PatchWall
    "com.mitv.tvhome.atv",          # 小米 Android TV 版 PatchWall
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
)


def get_signer():
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner

    os.makedirs(KEY_DIR, exist_ok=True)
    if not os.path.exists(KEY_PATH):
        keygen(KEY_PATH)
    with open(KEY_PATH, encoding="utf-8") as f:
        private = f.read()
    with open(KEY_PATH + ".pub", encoding="utf-8") as f:
        public = f.read()
    return PythonRSASigner(public, private)


def connect(host, port):
    from adb_shell.adb_device import AdbDeviceTcp

    device = AdbDeviceTcp(host, port, default_transport_timeout_s=120.0)
    device.connect(rsa_keys=[get_signer()], auth_timeout_s=120)
    return device


def run(device, command, quiet=False):
    if not quiet:
        print(f"$ {command}")
    output = device.shell(command, read_timeout_s=180.0)
    if not quiet and output and output.strip():
        print(output.strip())
    return output or ""


def installed_packages(device):
    output = run(device, "pm list packages", quiet=True)
    return {
        line.strip().replace("package:", "")
        for line in output.splitlines()
        if line.strip().startswith("package:")
    }


def main():
    parser = argparse.ArgumentParser(description="OpenTV Launcher 部署工具")
    parser.add_argument("--host", required=True, help="电视的局域网 IP")
    parser.add_argument("--port", type=int, default=5555)
    parser.add_argument("--probe", action="store_true", help="读取电视型号与系统版本")
    parser.add_argument("--install", metavar="APK", help="安装/覆盖安装 APK")
    parser.add_argument("--set-home", action="store_true", help="设为默认桌面")
    parser.add_argument("--disable-stock", metavar="PKG", help="停用原桌面（可用 --disable-stock auto 自动识别）")
    parser.add_argument("--restore-stock", metavar="PKG", help="恢复原桌面")
    parser.add_argument("--list-stock", action="store_true", help="列出常见的原桌面包名及其安装状态")
    args = parser.parse_args()

    device = connect(args.host, args.port)
    try:
        if args.probe or not any([args.install, args.set_home, args.disable_stock,
                                 args.restore_stock, args.list_stock]):
            for prop in ("ro.product.model", "ro.product.device",
                         "ro.build.version.release", "ro.build.version.sdk",
                         "ro.build.display.id"):
                run(device, f"getprop {prop}")

        if args.list_stock:
            packages = installed_packages(device)
            for pkg in COMMON_STOCK_LAUNCHERS:
                print(f"{pkg}: {'已安装' if pkg in packages else '未安装'}")

        if args.install:
            apk = os.path.abspath(args.install)
            if not os.path.exists(apk):
                sys.exit(f"找不到 APK: {apk}")
            size_mb = os.path.getsize(apk) / 1024 / 1024
            print(f"推送 {apk} ({size_mb:.1f} MB) ...")
            device.push(apk, REMOTE_APK)
            run(device, f"pm install -r {REMOTE_APK}")
            run(device, f"rm -f {REMOTE_APK}")

        if args.set_home:
            run(device, "cmd package set-home-activity --user 0 " + PACKAGE)
            run(device, "cmd package resolve-activity --brief -a android.intent.action.MAIN "
                        "-c android.intent.category.HOME")

        if args.disable_stock:
            target = args.disable_stock
            if target == "auto":
                packages = installed_packages(device)
                candidates = [p for p in COMMON_STOCK_LAUNCHERS if p in packages]
                if not candidates:
                    sys.exit("没有识别到常见的原桌面包，请用 --list-stock 查看后手动指定")
                target = candidates[0]
                print(f"自动识别原桌面: {target}")
            if target == PACKAGE:
                sys.exit("不能停用启动器自身")
            run(device, f"pm disable-user --user 0 {target}")

        if args.restore_stock:
            run(device, f"pm enable {args.restore_stock}")
            print("已恢复原桌面；如需切回，请在电视上重新选择默认桌面。")
    finally:
        device.close()

    print("完成。按遥控器上的 Home 键看看效果。")


if __name__ == "__main__":
    main()
