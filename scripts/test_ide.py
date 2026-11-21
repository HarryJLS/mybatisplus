#!/usr/bin/env python3
"""IntelliJ fixture tests isolated from the user's running IDEA configuration."""
import pathlib,os,subprocess,sys,urllib.request,zipfile
import build

ROOT=build.ROOT;out=ROOT/'build';idea=pathlib.Path(os.environ.get('IDEA_HOME','/Applications/IntelliJ IDEA.app/Contents'))
integrations='--integrations' in sys.argv
if (idea/'Contents').is_dir():idea=idea/'Contents'
product_info=idea/'Resources/product-info.json'
if not product_info.exists():product_info=idea/'product-info.json'
version=__import__('json').loads(product_info.read_text())['buildNumber']
deps=out/'test-deps';deps.mkdir(parents=True,exist_ok=True)
artifacts=[
 ('test-framework',f'https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/platform/test-framework/{version}/test-framework-{version}.jar'),
 ('java-test-framework',f'https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/java/java-test-framework/{version}/java-test-framework-{version}.jar'),
 ('junit','https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar'),
 ('hamcrest','https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest/2.2/hamcrest-2.2.jar'),
 ('opentest4j','https://repo.maven.apache.org/maven2/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar'),
 ('tests-bootstrap',f'https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/tools/tests-bootstrap/{version}/tests-bootstrap-{version}.jar'),
]
for name in ['test-framework-core','test-framework-common','test-framework-team-city']:
 artifacts.append((name,f'https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/platform/{name}/{version}/{name}-{version}.jar'))
for name in ['java-test-framework-shared','java-test-framework-backend']:
 artifacts.append((name,f'https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/java/{name}/{version}/{name}-{version}.jar'))
for name,url in artifacts:
 path=deps/(name+'.jar')
 if not path.exists() or not zipfile.is_zipfile(path):
  cache=out/(name+'.jar')
  if name=='test-framework':cache=out/'test-framework.complete.jar'
  if cache.exists() and zipfile.is_zipfile(cache):__import__('shutil').copy2(cache,path)
  else:
   print('Downloading test dependency:',name,flush=True)
   with urllib.request.urlopen(url,timeout=120) as r:path.write_bytes(r.read())
java=idea/'jbr/Contents/Home/bin'
if not (java/'javac').is_file():java=idea/'jbr/bin'
jars=list((idea/'lib').glob('*.jar'))+list((idea/'plugins/java/lib').glob('*.jar'))+list((idea/'plugins/java/lib/modules').glob('*.jar'))+list((out/'deps').glob('*.jar'))+list(deps.glob('*.jar'))
plugins=out/'plugin'
if integrations:
 from integration_sdk import selected_plugins
 selected=selected_plugins(idea)
 jars=list(dict.fromkeys(build.sdk_jars(idea)+list((out/'deps').glob('*.jar'))+list(deps.glob('*.jar'))+[jar for folder in selected for jar in (folder/'lib').rglob('*.jar')]))
 plugins=out/'integration-plugins';plugins.mkdir(exist_ok=True)
 for old in plugins.iterdir():
  if old.is_symlink():old.unlink()
 for source in selected:
  target=plugins/source.name
  if source.is_dir() and not target.exists():target.symlink_to(source,target_is_directory=True)
 target=plugins/'mybatis-assistant'
 if not target.exists():target.symlink_to(out/'plugin/mybatis-assistant',target_is_directory=True)
classes=out/('integration-test-classes' if integrations else 'ide-test-classes');classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[classes,out/'classes',*jars]))
sources=list((ROOT/'src/test/java/dev/mybatis/assistant/ide').glob('*.java'))
if integrations:sources+=list((ROOT/'src/test/java/dev/mybatis/assistant/integration').glob('*.java'))
try:build.run([java/'javac','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes,*sources])
except subprocess.CalledProcessError as e:sys.exit(e.returncode)
test_home=out/('ide-integration-tests' if integrations else 'ide-tests')
options=['-ea','-Xmx2048m' if integrations else '-Xmx1536m','--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true','-Didea.is.unit.test=true','-Didea.force.use.core.classloader=true','-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader','-Didea.home.path='+str(idea),'-Didea.plugins.path='+str(plugins),'-Didea.platform.prefix=Idea','-Djna.boot.library.path='+str(idea/'lib/jna/aarch64')]
for name in ['config','system','log']:options.append('-Didea.'+name+'.path='+str(test_home/name))
for package in ['java.lang','java.lang.reflect','java.io','java.nio','java.util','java.util.concurrent','sun.nio.ch','sun.nio.fs','jdk.internal.ref']:
 options.append('--add-opens=java.base/'+package+'=ALL-UNNAMED')
for package in ['sun.awt','sun.swing','sun.font','java.awt','java.awt.event','javax.swing','javax.swing.plaf.basic']:
 options.append('--add-opens=java.desktop/'+package+'=ALL-UNNAMED')
test_names=['dev.mybatis.assistant.'+('integration.' if source.parent.name=='integration' else 'ide.')+source.stem for source in sources if source.stem.endswith('Test')]
requested=[arg for arg in sys.argv[1:] if not arg.startswith('--')]
if requested:test_names=[name for name in test_names if name.rsplit('.',1)[-1] in requested]
elif integrations:test_names=[name for name in test_names if '.integration.' in name]
try:build.run([java/'java',*options,'-cp',cp,'org.junit.runner.JUnitCore',*test_names])
except subprocess.CalledProcessError as e:sys.exit(e.returncode)
