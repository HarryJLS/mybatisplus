#!/usr/bin/env python3
"""Name and verify the standard Gradle archive for this build host."""
import argparse
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from verify_archive import ROOT, PLATFORMS, host_platform, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', required=True, choices=PLATFORMS)
    args = parser.parse_args()
    if host_platform() != args.platform:
        raise SystemExit(f'Runner architecture {host_platform()} differs from matrix {args.platform}')
    version = ET.parse(ROOT / 'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
    gradle_version = re.search(r'^version\s*=\s*"([^"]+)"', (ROOT / 'build.gradle.kts').read_text(), re.M).group(1)
    if version != gradle_version:
        raise SystemExit('Gradle and plugin.xml versions differ')
    source = ROOT / f'build/distributions/mybatis-assistant-{version}-{args.platform}.zip'
    result = verify(source, args.platform)
    output = ROOT / 'build/ci-artifacts'
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f'mybatis-assistant-{version}-{args.platform}.zip'
    shutil.copy2(source, archive)
    result['archive'] = archive.name
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / f'SHA256SUMS-{args.platform}.txt').write_text(f'{digest}  {archive.name}\n', encoding='utf-8')
    (output / f'verification-{args.platform}.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
