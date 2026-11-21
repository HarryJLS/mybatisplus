#!/usr/bin/env python3
"""Load the native engine from the actual installable ZIP on this host."""
import argparse
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile
from verify_archive import ROOT, host_platform, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=pathlib.Path)
    parser.add_argument('--universal', action='store_true')
    args = parser.parse_args()
    verify(args.archive, 'universal' if args.universal else host_platform())
    java_home = pathlib.Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
    java = str(java_home / 'bin/java') if java_home else shutil.which('java')
    javac = str(java_home / 'bin/javac') if java_home else shutil.which('javac')
    with tempfile.TemporaryDirectory(prefix='mybatis-plugin-smoke-') as directory:
        folder = pathlib.Path(directory)
        jars = []
        with zipfile.ZipFile(args.archive) as z:
            for name in z.namelist():
                if name.endswith('.jar') and '/lib/' in name:
                    path = folder / pathlib.PurePosixPath(name).name
                    if path.exists():
                        raise ValueError(f'Duplicate plugin library name: {name}')
                    path.write_bytes(z.read(name))
                    jars.append(path)
        classpath = os.pathsep.join(map(str, jars))
        source = ROOT / 'src/test/java/dev/mybatis/assistant/packaging/PackagedNativeSmoke.java'
        subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(folder), str(source)], check=True)
        subprocess.run([java, '-ea', '-cp', str(folder) + os.pathsep + classpath,
                        'dev.mybatis.assistant.packaging.PackagedNativeSmoke'], check=True)
    print(f'PASS: installable plugin native loading on {host_platform()}')


if __name__ == '__main__':
    main()
