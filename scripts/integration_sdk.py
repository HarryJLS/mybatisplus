"""Select bundled integration plugins and their declared dependencies, not every IDE extension."""
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

def selected_plugins(idea):
    folders={};modules={};aliases={};root_dependencies={}
    for folder in (idea/'plugins').iterdir():
        if not folder.is_dir():continue
        main=None
        for jar in (folder/'lib').glob('*.jar'):
            with ZipFile(jar) as archive:
                if 'META-INF/plugin.xml' in archive.namelist():
                    try:root=ET.fromstring(archive.read('META-INF/plugin.xml'))
                    except ET.ParseError:continue
                    if root.findtext('id'):main=(jar,root);break
        if main is None:continue
        jar,root=main;folders[root.findtext('id')]=folder
        for alias in root.findall('module'):
            if alias.get('value'):aliases[alias.get('value')]=folder
        documents=[root]
        with ZipFile(jar) as archive:
            for include in root.findall('{http://www.w3.org/2001/XInclude}include'):
                if include.get('includeIf')=='idea.kotlin.plugin.use.k1':continue
                href=include.get('href','');name='META-INF/'+href
                if name in archive.namelist():
                    try:documents.append(ET.fromstring(archive.read(name)))
                    except ET.ParseError:pass
        dependencies=[]
        for document in documents:
            block=document.find('dependencies')
            if block is not None:dependencies += [('plugin',p.get('id')) if p.tag=='plugin' else ('module',p.get('name')) for p in block]
            dependencies += [('plugin',p.text) for p in document.findall('depends') if p.get('optional')!='true']
            for item in document.findall('./content/module'):
                name=item.get('name');module_root=None
                if item.text and item.text.strip().startswith('<idea-plugin'):
                    try:module_root=ET.fromstring(item.text.strip())
                    except ET.ParseError:pass
                if module_root is None:
                    for path in [folder/'lib/modules'/f'{name}.jar',folder/'lib'/f'{name}.jar']:
                        if not path.is_file():continue
                        with ZipFile(path) as archive:
                            for resource in ['META-INF/plugin.xml',f'META-INF/{name}.xml']:
                                if resource in archive.namelist():
                                    try:module_root=ET.fromstring(archive.read(resource))
                                    except ET.ParseError:pass
                                    break
                        if module_root is not None:break
                required=[]
                if module_root is not None:
                    block=module_root.find('dependencies')
                    if block is not None:required=[('plugin',p.get('id')) if p.tag=='plugin' else ('module',p.get('name')) for p in block]
                modules[name]=(folder,required)
                if item.get('loading-rule')=='required':dependencies.append(('module',name))
        root_dependencies[folder]=dependencies
    queue=[('plugin',name) for name in ['com.intellij.java','org.jetbrains.kotlin','com.intellij.spring','com.intellij.database']]
    queue += [('module',name) for name in ['intellij.spring','intellij.spring.core','intellij.database.core.impl','intellij.database.impl','intellij.database.connectivity']]
    selected=set();visited=set()
    while queue:
        kind,name=queue.pop()
        if not name or (kind,name) in visited:continue
        visited.add((kind,name))
        if kind=='module':
            found=modules.get(name)
            if found is None:continue
            folder,dependencies=found;queue += dependencies
        else:
            folder=folders.get(name,aliases.get(name))
            if folder is None:continue
        if folder not in selected:selected.add(folder);queue += root_dependencies.get(folder,[])
    return sorted(selected)
