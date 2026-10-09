#!/usr/bin/env python3
"""Prepare a saved-workflow import for explicit plan/implement/check/review/fix verification.
Writes JSON-form YAML; does not call models, import versions or run commands beyond reading Git HEAD.
"""
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--implementation',choices=['sol','flash'],default='sol')
p.add_argument('--planner-model',choices=['gpt-6-astra','gpt-6-luna'],default='gpt-6-astra',help='Explicit acceptance-test override; production workflow defaults to Astra')
a=p.parse_args();repo=a.repo.resolve();meta=repo/'.aiflow';meta.mkdir(exist_ok=True)
base=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip();(meta/'BASE_SHA').write_text(base+'\n')
common='Work only in this isolated aiflow checkout. Do not delegate, invoke other AI models, publish, push, change credentials, or edit .aiflow/runs or .aiflow/workflows. '
plan=common+'''Read 00-overview.md, 06-run-screen.md, scripts/phase3-result.md and relevant sections of airflow.md. Choose the next small unfinished Phase 3 code/UI quality unit based on that progression, without asking the user to choose. Exclude authentication, model contracts, packaging, remote publishing and broad redesign. Limit scope to at most 3 production files and 2 test files. Write a concise unit plan in .aiflow/UNIT_PLAN.md with problem, proposed changes, acceptance checks and files to inspect. Do not implement yet. Do not run Gradle. Reply with the plan path.'''
impl=common+'''Read .aiflow/UNIT_PLAN.md and implement that bounded unit. Concentrate on the unit plan and the specific files it names; consult the main plan only if necessary. Add meaningful regression tests. Do not run builds or test suites: a following shell step will do that without an AI call. Commit only the intended source/test/document changes using a conventional commit. Leave .aiflow artifacts untracked. Do not change the phase plans to claim acceptance checks passed. Reply briefly with the commit and changed files.'''
review=common+'''Continue the plan session. Review the implementation against .aiflow/UNIT_PLAN.md and the applicable main/Phase 3 plan. Read .aiflow/CHECK.log. Inspect the diff from the SHA in .aiflow/BASE_SHA to current HEAD; do not run tests or modify source. Write .aiflow/REVIEW.md: first line exactly APPROVED or CHANGES_REQUESTED, second line exact current HEAD SHA, third line exact SHA from .aiflow/BASE_SHA, then concise actionable findings with files. APPROVED requires successful recorded checks and the unit implemented. Reply with the verdict.'''
fix=common+'''Continue the implementation session. Read .aiflow/REVIEW.md and .aiflow/CHECK.log. If review is APPROVED and checks succeeded, make no changes and reply NO_CHANGES_NEEDED. Otherwise fix the actionable findings or check failures within .aiflow/UNIT_PLAN.md scope. Commit intended changes with a conventional commit. Do not run test suites or edit the review/check outputs: the following shell/review steps will do that. Reply with the resulting commit.'''
check="""(git diff --check && ./gradlew build --console=plain && python3 -m unittest discover -s scripts -p 'test_*.py') > .aiflow/CHECK.log 2>&1
check_rc=$?
printf '\\nCHECK_EXIT=%s\\n' "$check_rc" >> .aiflow/CHECK.log
tail -40 .aiflow/CHECK.log
exit "$check_rc"
"""
def edges(next):return [{'when':'success','next':next},{'when':'otherwise','next':'ask'}]
def agent(id,title,session,mode,script,next):
 implsession=session=='implementation';flash=implsession and a.implementation=='flash'
 return dict(id=id,title=title,session=dict(ref=session,mode=mode),model='gemini-3.8-flash-high' if flash else 'gpt-6-sol' if implsession else a.planner_model,effort='high' if flash else 'medium',script=script,timeoutSec=480,completion='exitCode',transitions=edges(next))
steps=[agent('plan','계획 · 새 세션 1','planning','new',plan,'implement'),agent('implement','구현 · 새 세션 2','implementation','new',impl,'verify'),dict(id='verify',title='검증 · AI 호출 없음',kind='shell',workspace='local',script=check,timeoutSec=600,transitions=edges('review')),agent('review','리뷰 · 세션 1 재개','planning','resume',review,'fix'),agent('fix','수정 · 세션 2 재개','implementation','resume',fix,'verify-final'),dict(id='verify-final',title='수정 후 검증 · AI 호출 없음',kind='shell',workspace='local',script=check,timeoutSec=600,transitions=edges('review-final')),agent('review-final','최종 리뷰 · 세션 1 재개','planning','resume',review,'approval-check'),dict(id='approval-check',title='승인 SHA 확인',kind='shell',workspace='local',script='''test "$(sed -n '1p' .aiflow/REVIEW.md)" = APPROVED &&
test "$(sed -n '2p' .aiflow/REVIEW.md)" = "$(git rev-parse HEAD)" &&
test "$(sed -n '3p' .aiflow/REVIEW.md)" = "$(cat .aiflow/BASE_SHA)" &&
test -z "$(git status --porcelain)" && printf 'REVIEWED_SHA=%s\\n' "$(git rev-parse HEAD)"''',transitions=edges('end'))]
workflow=dict(name='Phase 3 · 계획–구현–검증–리뷰–수정 ('+a.planner_model+' / '+a.implementation+')',repoPath=str(repo),baseBranch='main',start='plan',maxSteps=12,sessions=dict(planning=dict(provider='codex',workspace='local'),implementation=dict(provider='antigravity' if a.implementation=='flash' else 'codex',workspace='local')),steps=steps)
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(workflow,ensure_ascii=False,indent=2)+'\n');print(a.output.resolve())
