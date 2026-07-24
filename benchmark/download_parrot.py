#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PARROT 数据集极速与高可靠下载器 (带镜像加速、重定向跟进、重试与降级)
"""

import sys
import os
import time
import urllib.request
import shutil

DEFAULT_MIRROR_URL = "https://hf-mirror.com/datasets/weizhoudb/PARROT/resolve/main/parrot_diverse.json"
BACKUP_HF_URL = "https://huggingface.co/datasets/weizhoudb/PARROT/resolve/main/parrot_diverse.json"
LOCAL_BUILTIN_PATH = "sdt-core/src/test/resources/parrot/parrot_full_cases.json"
TARGET_OUTPUT_PATH = "benchmark/parrot-data/parrot_dataset.json"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

def download_file(url, target_path, timeout=120):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
        # 校验：大小必须大于 1KB，且不能是 Git LFS 指针文件
        if len(data) > 1000 and not data.startswith(b"version https://git-lfs"):
            os.makedirs(os.path.dirname(target_path), exist_ok=True)
            with open(target_path, "wb") as f:
                f.write(data)
            return len(data)
    return 0

def main():
    custom_url = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].strip() else ""
    url_to_try = custom_url if custom_url else DEFAULT_MIRROR_URL

    print(f"Starting PARROT dataset download from: {url_to_try}")
    
    downloaded_size = 0
    # 尝试 1：优先的主下载链接（镜像加速）
    for attempt in range(1, 4):
        try:
            print(f"Attempt {attempt}/3: Fetching...")
            downloaded_size = download_file(url_to_try, TARGET_OUTPUT_PATH)
            if downloaded_size > 0:
                print(f"[OK] Successfully downloaded dataset ({downloaded_size} bytes)")
                break
        except Exception as e:
            print(f"Attempt {attempt} failed: {e}")
            time.sleep(2)

    # 尝试 2：如果主链接失败，尝试备份官方 URL
    if downloaded_size == 0 and url_to_try != BACKUP_HF_URL:
        print(f"Trying backup official URL: {BACKUP_HF_URL}")
        try:
            downloaded_size = download_file(BACKUP_HF_URL, TARGET_OUTPUT_PATH)
            if downloaded_size > 0:
                print(f"[OK] Backup download succeeded ({downloaded_size} bytes)")
        except Exception as e:
            print(f"Backup download failed: {e}")

    # 尝试 3：若网络下载均未成功，自动退回使用 SDT 内置全量评测数据集
    if downloaded_size == 0 or not os.path.exists(TARGET_OUTPUT_PATH):
        print("Remote downloads failed or invalid, falling back to SDT builtin dataset...")
        os.makedirs(os.path.dirname(TARGET_OUTPUT_PATH), exist_ok=True)
        shutil.copy(LOCAL_BUILTIN_PATH, TARGET_OUTPUT_PATH)
        print(f"[OK] Fallback completed. Prepared builtin dataset.")

    final_size = os.path.getsize(TARGET_OUTPUT_PATH)
    print(f"Dataset ready at {TARGET_OUTPUT_PATH} (Size: {final_size} bytes)")

if __name__ == "__main__":
    main()
