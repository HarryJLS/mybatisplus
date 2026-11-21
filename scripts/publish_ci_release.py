#!/usr/bin/env python3
"""Publish only a complete, checksum-verified set of platform artifacts."""
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from verify_archive import ROOT, verify

TARGETS = ('macos-aarch64', 'macos-x86_64', 'linux-x86_64', 'windows-x86_64')


def main():
    version = ET.parse(ROOT / 'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
    folder = ROOT / 'build/release-assets'
    assets = []
    for target in TARGETS:
        archive = folder / f'mybatis-assistant-{version}-{target}.zip'
        checksum = folder / f'SHA256SUMS-{target}.txt'
        report = folder / f'verification-{target}.json'
        verify(archive, target)
        digest, filename = checksum.read_text(encoding='utf-8').strip().split('  ', 1)
        if filename != archive.name or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise SystemExit(f'Checksum mismatch: {archive.name}')
        result = json.loads(report.read_text(encoding='utf-8'))
        if result['status'] != 'passed' or result['platform'] != target or result['version'] != version:
            raise SystemExit(f'Invalid verification report: {target}')
        assets.extend([archive, checksum, report])
    number = os.environ['BUILD_NUMBER']
    sha = os.environ['BUILD_SHA']
    tag = f'v{version}-build.{number}'
    notes = ROOT / 'build/release-notes.md'
    notes.write_text(
        f'MyBatis Assistant {version}，自动构建 #{number}。\n\n'
        f'源码：`{sha}`\n\n[构建与测试记录]({os.environ["BUILD_URL"]})\n\n'
        '适用 IntelliJ IDEA 2026.1（261.*）、Java 21+。按系统和处理器选择 ZIP；'
        'macos-aarch64 为 Apple Silicon，macos-x86_64 为 Intel Mac，'
        'linux-x86_64 和 windows-x86_64 为对应系统的 Intel/AMD 64 位包。\n\n'
        'IDEA → Settings → Plugins → Install Plugin from Disk，选择 ZIP 后重启。\n\n'
        '验证：四个平台的 Rust 测试、Java/JNI/MyBatis 核心断言、注册实现/原生库架构/归档检查。'
        '包含 SHA256 校验文件与归档报告。云端构建未运行 IDEA 界面集成测试或真实生产数据库验证。\n',
        encoding='utf-8')
    existing = subprocess.run(['gh', 'release', 'view', tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if existing.returncode == 0:
        subprocess.run(['gh', 'release', 'upload', tag, *map(str, assets), '--clobber'], check=True)
    else:
        subprocess.run(['gh', 'release', 'create', tag, *map(str, assets), '--target', sha,
                        '--title', f'MyBatis Assistant {version} · Build {number}', '--notes-file', str(notes), '--latest'], check=True)


if __name__ == '__main__':
    main()
