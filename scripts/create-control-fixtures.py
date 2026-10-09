#!/usr/bin/env python3
"""Generate offline shell workflows for app button/recovery/log acceptance."""
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args();root=a.output.resolve();root.mkdir(parents=True,exist_ok=True);repo=root/'repo';repo.mkdir(exist_ok=True)
subprocess.run(['git','init','-q',str(repo)],check=True)
def edge(condition,target):return {'when':condition,'next':target}
def step(script,completion=None):
 s={'id':'body','title':'버튼 검증','kind':'shell','workspace':'local','script':script,'transitions':[edge('success','end'),edge('otherwise','ask')]}
 if completion:s['completion']={'command':completion}
 return s
cases={
 'retry':step('if test ! -f .sleepworker/retry-marker; then touch .sleepworker/retry-marker; echo FIRST_FAILURE; exit 7; fi; echo RETRY_OK'),
 'manual':step('echo MANUAL_FAILURE; exit 7'),
 'abort-body':step('echo $$ > .sleepworker/body.pid\nsleep 120 &\necho $! > .sleepworker/child.pid\nwait'),
 'abort-check':step('echo BODY_DONE', 'echo $$ > .sleepworker/check.pid; sleep 120'),
 'recovery':step('echo $$ > .sleepworker/recovery.pid\necho RECOVERY_RUNNING\nsleep 120'),
 'logs':step("for i in {1..25000}; do printf 'line-%s\\n' $i; done; echo LOGS_COMPLETE; sleep 2"),
}
for name,s in cases.items():
 w={'name':'버튼 검증 · '+name,'repoPath':str(repo),'baseBranch':'main','start':'body','maxSteps':4,'steps':[s]}
 (root/(name+'.yaml')).write_text(json.dumps(w,ensure_ascii=False,indent=2)+'\n')
print(repo)
