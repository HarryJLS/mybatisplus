#!/usr/bin/env python3
"""Run differential tests with test-only iBATIS 2; it is not bundled into the plugin."""
import os,pathlib,hashlib,shutil,subprocess,sys,urllib.request
import build

root=build.ROOT;deps=root/'build/compat-deps';deps.mkdir(parents=True,exist_ok=True)
name='ibatis-sqlmap-2.3.4.726.jar';jar=deps/name
if not jar.exists():
 cached=root/'build/example-deps'/name
 if cached.exists():shutil.copy2(cached,jar)
 else:
  with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/apache/ibatis/ibatis-sqlmap/2.3.4.726/'+name,timeout=60) as response:jar.write_bytes(response.read())
if hashlib.sha256(jar.read_bytes()).hexdigest()!='0b7d2fc8284bdcf9428e9e468c11a421ecba75a10f159a95e8feedbc304dc8ec':sys.exit('iBATIS oracle checksum mismatch')
idea=pathlib.Path(os.environ.get('IDEA_HOME','/Applications/IntelliJ IDEA.app/Contents'))
if (idea/'Contents').is_dir():idea=idea/'Contents'
java=idea/'jbr/Contents/Home/bin'
if not (java/'javac').exists():java=pathlib.Path(shutil.which('javac')).parent
classes=root/'build/compat-test-classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[classes,root/'build/classes',jar,*sorted((root/'build/deps').glob('*.jar'))]))
sources=sorted((root/'src/test/java/dev/mybatis/assistant/compat').glob('*.java'))
try:
 build.run([java/'javac','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes,*sources])
 build.run([java/'java','-ea','-cp',cp,'dev.mybatis.assistant.compat.IbatisOracleTests'])
except subprocess.CalledProcessError as error:sys.exit(error.returncode)
