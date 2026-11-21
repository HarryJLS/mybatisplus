#!/usr/bin/env python3
"""Inventory plugin declarations and resource categories without extracting executable code."""
import argparse,json,pathlib,re,zipfile,xml.etree.ElementTree as ET
from collections import Counter

def main():
 p=argparse.ArgumentParser();p.add_argument('directory',nargs='?',default='MyBatisCodeHelper-Pro');p.add_argument('--output',default='docs/reference-inventory.json');args=p.parse_args()
 base=pathlib.Path(args.directory)/'lib';jars=sorted(base.glob('*.jar'))
 main=next((f for f in jars if f.name.startswith('instrumented-MyBatisCodeHelper')),None)
 if main is None:raise SystemExit('Main plugin JAR not found')
 with zipfile.ZipFile(main) as z:
  entries=z.namelist();resources=[e for e in entries if not e.endswith(('.class','/'))]
  descriptors={};version=None;plugin_id=None
  for path in resources:
   if path.startswith('META-INF/') and path.endswith('.xml'):
    try:root=ET.fromstring(z.read(path))
    except ET.ParseError as error:
     descriptors[path]={'extensionCount':0,'parseNote':str(error)};continue
    extensions=[e for block in root.findall('extensions') for e in block]
    descriptors[path]={'extensionCount':len(extensions),'extensionTypes':dict(Counter(e.tag for e in extensions)),
      'inspectionNames':[e.get('shortName',e.get('displayName','')) for e in extensions if e.tag=='localInspection'],
      'actionCount':len(root.findall('.//action'))}
    if path=='META-INF/plugin.xml':version=root.findtext('version');plugin_id=root.findtext('id')
  templates={}
  for path in resources:
   if path.startswith('templates/'):
    text=z.read(path).decode('utf-8');templates[path]=sorted(set(re.findall(r'\$\{([^}]+)}',text)))
  report={'referencePluginId':plugin_id,'referenceVersion':version,'mainJar':main.name,
   'classCount':sum(e.endswith('.class') for e in entries),'resourceCount':len(resources),
   'libraryCount':len(jars),'libraryBytes':sum(f.stat().st_size for f in jars),
   'resourcePaths':resources,'descriptors':descriptors,'templateVariables':templates,
   'libraries':[{'name':f.name,'bytes':f.stat().st_size} for f in jars]}
 output=pathlib.Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(f'Parsed {version}: {len(jars)} libraries, {report["classCount"]} classes, {len(resources)} resources; saved {output}')
if __name__=='__main__':main()
