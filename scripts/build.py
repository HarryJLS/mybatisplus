#!/usr/bin/env python3
"""Build against a local IDEA SDK without downloading another IDE or a Gradle daemon."""
import argparse, hashlib, os, pathlib, platform, re, shutil, subprocess, sys, urllib.request, zipfile, xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD=ROOT/'build'
def sdk_jars(idea,integrations=True):
    jars=list((idea/'lib').glob('*.jar'))+list((idea/'plugins/java/lib').glob('*.jar'))+list((idea/'plugins/java/lib/modules').glob('*.jar'))
    if integrations:
        for name in ['DatabaseTools','Spring','Kotlin']:
            jars.extend((idea/'plugins'/name/'lib').rglob('*.jar'))
    return list(dict.fromkeys(jars))
VERSION=ET.parse(ROOT/'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
DEPENDENCIES=[
    ('mybatis-3.5.19.jar','https://repo.maven.apache.org/maven2/org/mybatis/mybatis/3.5.19/mybatis-3.5.19.jar','93eea616ae355751bd5fbabb57f0732713fbe79f3196f33c51a0aeeb4255862a'),
    ('gson-2.13.2.jar','https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar','dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0'),
]
def run(args,**kwargs):
    display=[];hide_next=False
    for a in args:
        if hide_next:display.append('<classpath>');hide_next=False;continue
        display.append(str(a));hide_next=str(a) in ('-cp','-classpath')
    print('+', ' '.join(display),flush=True)
    subprocess.run([str(a) for a in args],check=True,cwd=ROOT,**kwargs)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--idea-home',default=os.environ.get('IDEA_HOME','/Applications/IntelliJ IDEA.app/Contents'))
    parser.add_argument('--test',action='store_true')
    parser.add_argument('--bench',action='store_true')
    args=parser.parse_args();idea=pathlib.Path(args.idea_home)
    if (idea/'Contents').is_dir():idea=idea/'Contents'
    if not (idea/'lib').is_dir():sys.exit('IDEA SDK directory not found; set IDEA_HOME or --idea-home')
    candidates=[]
    if os.environ.get('JAVA_HOME'):candidates.append(pathlib.Path(os.environ['JAVA_HOME'])/'bin')
    candidates.extend([idea/'jbr/Contents/Home/bin',idea/'jbr/bin'])
    if shutil.which('javac'):candidates.append(pathlib.Path(shutil.which('javac')).parent)
    java=None
    for candidate in candidates:
        if not (candidate/'javac').is_file():continue
        version=subprocess.run([str(candidate/'javac'),'-version'],capture_output=True,text=True).stdout
        match=re.search(r'javac (\d+)',version)
        if match and int(match[1])>=21:java=candidate;break
    if java is None:sys.exit('JDK 21+ not found; set JAVA_HOME to a JDK 21 or newer installation')
    deps=BUILD/'deps';deps.mkdir(parents=True,exist_ok=True)
    for name,url,digest in DEPENDENCIES:
        path=deps/name
        if not path.exists():
            cached=pathlib.Path('/tmp')/name
            if cached.exists():shutil.copy2(cached,path)
            else:
                with urllib.request.urlopen(url,timeout=60) as response:path.write_bytes(response.read())
        if digest and hashlib.sha256(path.read_bytes()).hexdigest()!=digest:sys.exit('Dependency checksum mismatch: '+name)
    if args.test:run(['cargo','test','--locked','--manifest-path','native/Cargo.toml'])
    run(['cargo','build','--release','--locked','--manifest-path','native/Cargo.toml'])
    os_name={'Darwin':'macos','Linux':'linux','Windows':'windows'}[platform.system()]
    arch='aarch64' if platform.machine().lower() in ('arm64','aarch64') else 'x86_64'
    filename={'macos':'libmybatis_engine.dylib','linux':'libmybatis_engine.so','windows':'mybatis_engine.dll'}[os_name]
    library=ROOT/'native/target/release'/filename
    classpath=os.pathsep.join(str(p) for p in sdk_jars(idea)+list(deps.glob('*.jar')))
    classes=BUILD/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))
    argfile=BUILD/'javac.args'
    argfile.write_text('\n'.join('"'+str(p)+'"' for p in sources))
    run([java/'javac','--release','21','-encoding','UTF-8','-cp',classpath,'-d',classes,'@'+str(argfile)])
    shutil.copytree(ROOT/'src/main/resources',classes,dirs_exist_ok=True)
    for notice in ['LICENSE','THIRD_PARTY_NOTICES.md']:
        if (ROOT/notice).is_file():shutil.copy2(ROOT/notice,classes/'META-INF'/notice)
    native_dir=classes/'native'/f'{os_name}-{arch}';native_dir.mkdir(parents=True,exist_ok=True);shutil.copy2(library,native_dir/filename)
    plugin_dir=BUILD/'plugin/mybatis-assistant/lib';shutil.rmtree(BUILD/'plugin',ignore_errors=True);plugin_dir.mkdir(parents=True)
    with zipfile.ZipFile(plugin_dir/f'mybatis-assistant-{VERSION}.jar','w',zipfile.ZIP_DEFLATED) as jar:
        for f in sorted(classes.rglob('*')):
            if f.is_file():jar.write(f,f.relative_to(classes))
    for dep in deps.glob('*.jar'):shutil.copy2(dep,plugin_dir/dep.name)
    dist=BUILD/'distributions';dist.mkdir(exist_ok=True)
    archive=dist/f'mybatis-assistant-{VERSION}-{os_name}-{arch}.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for f in sorted((BUILD/'plugin').rglob('*')):
            if f.is_file():z.write(f,f.relative_to(BUILD/'plugin'))
    if args.test:
        tests=sorted((ROOT/'src/test/java/dev/mybatis/assistant/core').rglob('*.java'));test_classes=BUILD/'test-classes';test_classes.mkdir(exist_ok=True)
        run([java/'javac','--release','21','-encoding','UTF-8','-cp',str(classes)+os.pathsep+classpath,'-d',test_classes,*tests])
        runtime_args=['--enable-native-access=ALL-UNNAMED'] if int(subprocess.run([java/'javac','-version'],capture_output=True,text=True).stdout.split()[1].split('.')[0])>=24 else []
        run([java/'java',*runtime_args,'-ea','-Didea.system.path='+str(BUILD/'test-system'),'-Dmybatis.assistant.native='+str(library),'-cp',os.pathsep.join([str(test_classes),str(classes),classpath]),'dev.mybatis.assistant.core.CoreTests'])
    if args.bench:run([ROOT/'native/target/release/mybatis-engine','--bench'])
    print('\nInstallable plugin:',archive)
    print('Plugin ZIP bytes:',archive.stat().st_size)
if __name__=='__main__':
    try:main()
    except subprocess.CalledProcessError as error:sys.exit(error.returncode)
