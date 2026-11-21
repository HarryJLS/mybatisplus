#!/usr/bin/env python3
"""Verify plugin metadata, registered implementations and platform native code."""
import argparse
import io
import json
import pathlib
import platform
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLATFORMS = {
    'macos-aarch64': ('libmybatis_engine.dylib', 'macho', 0x0100000c),
    'macos-x86_64': ('libmybatis_engine.dylib', 'macho', 0x01000007),
    'linux-aarch64': ('libmybatis_engine.so', 'elf', 183),
    'linux-x86_64': ('libmybatis_engine.so', 'elf', 62),
    'windows-aarch64': ('mybatis_engine.dll', 'pe', 0xaa64),
    'windows-x86_64': ('mybatis_engine.dll', 'pe', 0x8664),
}


def host_platform():
    system = {'Darwin': 'macos', 'Linux': 'linux', 'Windows': 'windows'}[platform.system()]
    arch = 'aarch64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x86_64'
    return f'{system}-{arch}'


def verify(archive, target):
    version = ET.parse(ROOT / 'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
    library, binary_format, machine = PLATFORMS[target]
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None, 'Corrupt ZIP'
        assert not any('MyBatisCodeHelper-Pro' in n for n in z.namelist()), 'Reference resources in package'
        assert not any(pathlib.PurePosixPath(n).name.startswith(('h2-', 'ibatis-', 'junit-', 'hamcrest-', 'test-framework'))
                       for n in z.namelist()), 'Test dependencies in package'
        plugin_jar = next(n for n in z.namelist() if n.endswith(f'/mybatis-assistant-{version}.jar'))
        with zipfile.ZipFile(io.BytesIO(z.read(plugin_jar))) as jar:
            assert jar.testzip() is None, 'Corrupt plugin JAR'
            names = set(jar.namelist())
            plugin = ET.fromstring(jar.read('META-INF/plugin.xml'))
            assert plugin.findtext('id') == 'dev.mybatis.assistant'
            assert plugin.findtext('version') == version
            assert plugin.find('idea-version').get('since-build') == '261'
            assert plugin.find('idea-version').get('until-build') == '261.*'
            implementations = []
            descriptors = [plugin]
            for dependency in plugin.findall('depends'):
                config = dependency.get('config-file')
                if config:
                    assert 'META-INF/' + config in names, config
                    descriptors.append(ET.fromstring(jar.read('META-INF/' + config)))
            for descriptor in descriptors:
                for e in descriptor.iter():
                    for attr in ['implementation', 'implementationClass', 'serviceImplementation', 'instance', 'factoryClass', 'class']:
                        value = e.get(attr)
                        if value and value.startswith('dev.mybatis.assistant'):
                            implementations.append(value)
                    if e.tag == 'className' and e.text:
                        implementations.append(e.text)
            for impl in implementations:
                assert impl.replace('.', '/') + '.class' in names, impl
            native = f'native/{target}/{library}'
            assert native in names, native
            assert {n for n in names if n.startswith('native/') and not n.endswith('/')} == {native}, 'Unexpected platform libraries'
            data = jar.read(native)
            if binary_format == 'macho':
                assert data[:4] == bytes.fromhex('cffaedfe'), 'Expected 64-bit Mach-O'
                actual_machine = struct.unpack_from('<I', data, 4)[0]
            elif binary_format == 'elf':
                assert data[:6] == b'\x7fELF\x02\x01', 'Expected 64-bit little-endian ELF'
                actual_machine = struct.unpack_from('<H', data, 18)[0]
            else:
                assert data[:2] == b'MZ', 'Expected PE DLL'
                offset = struct.unpack_from('<I', data, 0x3c)[0]
                assert data[offset:offset + 4] == b'PE\x00\x00', 'Invalid PE header'
                actual_machine = struct.unpack_from('<H', data, offset + 4)[0]
            assert actual_machine == machine, f'Wrong native architecture for {target}'
            assert 'META-INF/LICENSE' in names and 'META-INF/THIRD_PARTY_NOTICES.md' in names
            assert not any(n.endswith('.class') and not n.startswith('dev/mybatis/assistant/') for n in names)
    return {'archive': archive.name, 'bytes': archive.stat().st_size, 'pluginId': plugin.findtext('id'),
            'version': version, 'platform': target, 'registeredImplementationsChecked': len(implementations),
            'nativeLibraryBytes': len(data), 'status': 'passed'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', nargs='?', type=pathlib.Path)
    parser.add_argument('--platform', choices=PLATFORMS)
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'build/archive-verification.json')
    args = parser.parse_args()
    target = args.platform or host_platform()
    archive = args.archive
    if archive is None:
        archives = list((ROOT / 'build/distributions').glob(f'mybatis-assistant-*-{target}.zip'))
        if not archives:
            raise SystemExit('Build the platform ZIP first or supply its path')
        archive = max(archives, key=lambda p: p.stat().st_mtime)
    result = verify(archive, target)
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    print(encoded, end='')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(encoded, encoding='utf-8')


if __name__ == '__main__':
    main()
