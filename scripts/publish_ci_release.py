#!/usr/bin/env python3
"""Publish the verified universal installable plugin ZIP."""
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from verify_archive import ROOT, verify


def main():
    version = ET.parse(ROOT / 'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
    folder = ROOT / 'build/release-assets'
    archive = folder / f'mybatis-assistant-{version}.zip'
    checksum = folder / 'SHA256SUMS.txt'
    report = folder / 'verification.json'
    verify(archive, 'universal')
    digest, filename = checksum.read_text(encoding='utf-8').strip().split('  ', 1)
    if filename != archive.name or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise SystemExit(f'Checksum mismatch: {archive.name}')
    result = json.loads(report.read_text(encoding='utf-8'))
    if (result['status'], result['platform'], result['version']) != ('passed', 'universal', version):
        raise SystemExit('Invalid universal verification report')
    assets = [archive, checksum, report]
    number = os.environ['BUILD_NUMBER']
    sha = os.environ['BUILD_SHA']
    tag = f'v{version}-build.{number}'
    notes = ROOT / 'build/release-notes.md'
    notes.write_text(
        f'MyBatis Assistant {version}，自动构建 #{number}。\n\n'
        f'源码：`{sha}`\n\n[构建与测试记录]({os.environ["BUILD_URL"]})\n\n'
        f'**插件安装包：`{archive.name}`**，同一个 ZIP 用于 Windows x86_64、'
        'macOS Apple Silicon / Intel 和 Linux x86_64，插件自动选择内置原生库。'
        '适用 IntelliJ IDEA 2026.1（261.*）、Java 21+。\n\n'
        'IDEA → Settings → Plugins → Install Plugin from Disk，选择插件 ZIP 后重启，无需解压。'
        'GitHub 自动附加的 Source code ZIP 是源码，不能直接安装。\n\n'
        '验证：四个平台的 Rust 测试、Java/JNI/MyBatis 核心断言，统一 ZIP 的注册实现、'
        '原生库架构和完整性校验，以及四个平台从最终 ZIP 自动加载 JNI 的日志/DDL 测试。'
        '包含 SHA256 校验文件与归档报告。未扩大为 IDEA 界面集成或真实生产数据库验证。\n',
        encoding='utf-8')
    existing = subprocess.run(['gh', 'release', 'view', tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if existing.returncode == 0:
        subprocess.run(['gh', 'release', 'upload', tag, *map(str, assets), '--clobber'], check=True)
    else:
        subprocess.run(['gh', 'release', 'create', tag, *map(str, assets), '--target', sha,
                        '--title', f'MyBatis Assistant {version} · Build {number}', '--notes-file', str(notes), '--latest'], check=True)


if __name__ == '__main__':
    main()
