#!/usr/bin/env python3
"""Combine matching platform builds into one installable IntelliJ plugin ZIP."""
import argparse
import hashlib
import io
import json
import pathlib
import xml.etree.ElementTree as ET
import zipfile
from verify_archive import ROOT, PLATFORMS, UNIVERSAL_PLATFORMS, verify


def file_entries(archive):
    with zipfile.ZipFile(archive) as z:
        return {n: z.read(n) for n in z.namelist() if not n.endswith('/')}


def plugin_entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        return {n: jar.read(n) for n in jar.namelist() if not n.endswith('/')}


def encode_zip(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            z.writestr(info, entries[name])
    return output.getvalue()


def assemble(input_dir, output_dir):
    version = ET.parse(ROOT / 'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
    base = None
    common = None
    jar_name = None
    natives = {}
    for target in UNIVERSAL_PLATFORMS:
        matches = list(input_dir.rglob(f'mybatis-assistant-{version}-{target}.zip'))
        if len(matches) != 1:
            raise ValueError(f'Expected exactly one platform build: {target}')
        archive = matches[0]
        verify(archive, target)
        checksum = archive.parent / f'SHA256SUMS-{target}.txt'
        digest, filename = checksum.read_text(encoding='utf-8').strip().split('  ', 1)
        if filename != archive.name or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise ValueError(f'Checksum mismatch: {archive.name}')
        report = json.loads((archive.parent / f'verification-{target}.json').read_text(encoding='utf-8'))
        if (report['status'], report['platform'], report['version']) != ('passed', target, version):
            raise ValueError(f'Invalid platform verification: {target}')
        files = file_entries(archive)
        name = next(n for n in files if n.endswith(f'/mybatis-assistant-{version}.jar'))
        entries = plugin_entries(files[name])
        shared = {n: data for n, data in entries.items() if not n.startswith('native/')}
        dependencies = {n: data for n, data in files.items() if n != name}
        # Manifest records the build host; code, descriptors and dependencies must match.
        comparison = {n: data for n, data in shared.items() if n != 'META-INF/MANIFEST.MF'}
        if base is None:
            base, common, jar_name = files, comparison, name
            merged = shared
            common_dependencies = dependencies
        elif name != jar_name or comparison != common or dependencies != common_dependencies:
            raise ValueError(f'Platform builds contain different plugin code or dependencies: {target}')
        native_name = f'native/{target}/{PLATFORMS[target][0]}'
        natives[native_name] = entries[native_name]
    merged.update(natives)
    base[jar_name] = encode_zip(merged)
    output_dir.mkdir(parents=True, exist_ok=True)
    archive = output_dir / f'mybatis-assistant-{version}.zip'
    archive.write_bytes(encode_zip(base))
    result = verify(archive, 'universal')
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output_dir / 'SHA256SUMS.txt').write_text(f'{digest}  {archive.name}\n', encoding='utf-8')
    (output_dir / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', required=True, type=pathlib.Path)
    parser.add_argument('--output-dir', default=ROOT / 'build/universal-artifacts', type=pathlib.Path)
    args = parser.parse_args()
    assemble(args.input_dir, args.output_dir)


if __name__ == '__main__':
    main()
