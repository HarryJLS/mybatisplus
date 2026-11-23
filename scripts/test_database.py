#!/usr/bin/env python3
"""Test-only H2 driver, isolated JDBC transactions; never opens a user data source."""
import hashlib,os,pathlib,subprocess,sys,urllib.request
import build
root=build.ROOT;jar=root/'build/test-deps/h2-2.3.232.jar';jar.parent.mkdir(parents=True,exist_ok=True)
if not jar.exists():
 with urllib.request.urlopen('https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar',timeout=60) as response:jar.write_bytes(response.read())
if hashlib.sha256(jar.read_bytes()).hexdigest()!='8dae62d22db8982c3dcb3826edb9c727c5d302063a67eef7d63d82de401f07d3':raise SystemExit('H2 test driver checksum mismatch')
idea=pathlib.Path(os.environ.get('IDEA_HOME','/Applications/IntelliJ IDEA.app/Contents'))
if (idea/'Contents').is_dir():idea=idea/'Contents'
java=idea/'jbr/Contents/Home/bin'
if not (java/'javac').exists():java=idea/'jbr/bin'
classes=root/'build/database-test-classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[classes,root/'build/classes',root/'build/test-classes',*sorted((root/'build/plugin/mybatis-assistant/lib').glob('*.jar'))]))
sources=sorted((root/'src/test/java/dev/mybatis/assistant/database').glob('*.java'))
try:
 build.run([java/'javac','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes,*sources])
 build.run([java/'java','-ea','-Dmybatis.test.h2='+str(jar),'-cp',cp,'dev.mybatis.assistant.database.JdbcExecutionTests'])
 build.run([java/'java','-ea','-Dmybatis.test.h2='+str(jar),'-cp',cp,'dev.mybatis.assistant.core.GeneratedMapperTestTests'])
except subprocess.CalledProcessError as error:sys.exit(error.returncode)
