#!/usr/bin/env python3
"""Finite external sampling of an explicitly selected IDEA PID; never installed in the plugin."""
import argparse,datetime,json,pathlib,re,statistics,subprocess,time

def run(command):
    return subprocess.check_output(command,text=True,timeout=20,stderr=subprocess.STDOUT).strip()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pid',required=True,type=int)
    parser.add_argument('--seconds',type=int,default=1200)
    parser.add_argument('--interval',type=int,default=5)
    parser.add_argument('--jcmd',type=pathlib.Path,default=pathlib.Path('/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/jcmd'))
    parser.add_argument('--output',required=True,type=pathlib.Path)
    args=parser.parse_args()
    if not 10<=args.seconds<=86400 or not 1<=args.interval<=60:parser.error('bounded duration/interval required')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    start=time.monotonic();samples=[];next_heap=0;next_report=60
    while time.monotonic()-start<=args.seconds:
        elapsed=time.monotonic()-start
        sample={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsedSeconds':round(elapsed,3)}
        try:
            fields=run(['ps','-p',str(args.pid),'-o','%cpu=,rss=']).split()
            sample.update(cpuPercentOneCore=float(fields[0]),rssBytes=int(fields[1])*1024)
            if elapsed>=next_heap:
                text=run([str(args.jcmd),str(args.pid),'GC.heap_info'])
                used=re.search(r'used ([0-9]+)K',text);committed=re.search(r'committed ([0-9]+)K',text)
                if used:sample['heapUsedBytes']=int(used[1])*1024
                if committed:sample['heapCommittedBytes']=int(committed[1])*1024
                next_heap=elapsed+30
        except (subprocess.SubprocessError,ValueError,IndexError) as failure:
            sample['error']=str(failure);samples.append(sample);break
        samples.append(sample)
        args.output.write_text(json.dumps({'pid':args.pid,'requestedSeconds':args.seconds,'scope':'whole IDEA process; ps CPU uses one-core=100%; GC.heap_info does not force GC','samples':samples},indent=2)+'\n')
        if elapsed>=next_report:
            print(f'Sampled {elapsed:.0f}s: CPU {sample["cpuPercentOneCore"]:.1f}%, RSS {sample["rssBytes"]/1048576:.1f} MiB',flush=True);next_report+=60
        remaining=args.seconds-(time.monotonic()-start)
        if remaining<=0:break
        time.sleep(min(args.interval,remaining))
    valid=[sample for sample in samples if 'rssBytes' in sample]
    summary={'samples':len(valid),'actualSeconds':round(time.monotonic()-start,3)}
    if valid:
        cpu=[sample['cpuPercentOneCore'] for sample in valid];rss=[sample['rssBytes'] for sample in valid]
        summary.update(cpuMedianPercentOneCore=statistics.median(cpu),cpuMeanPercentOneCore=statistics.mean(cpu),cpuMaxPercentOneCore=max(cpu),rssFirstBytes=rss[0],rssLastBytes=rss[-1],rssMaxBytes=max(rss))
    args.output.with_suffix('.summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary),flush=True)

if __name__=='__main__':main()
