"""Summarize a recorded real-AI run locally; never calls a provider."""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def pct(n,d):return round(100*n/d,2) if d else None
def p95(values):return round(sorted(values)[math.ceil(len(values)*.95)-1],2) if values else None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run_dir',type=Path)
    args=parser.parse_args()
    folder=args.run_dir.resolve()
    report=json.loads((folder/'report.json').read_text(encoding='utf-8'))
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    review_path=folder/'semantic-review.json'
    reviews=json.loads(review_path.read_text(encoding='utf-8')) if review_path.exists() else {}
    support=[]
    for result in report['support']:
        item=dict(result)
        review=reviews.get(item['id'])
        item['semantic_review']=review or dict(result='PENDING',reason='Awaiting response review')
        if item['check']!='PASS' or item.get('handoff_check','PASS')!='PASS':item['overall']='FAIL'
        elif not review:item['overall']='PENDING'
        else:item['overall']=review['result']
        support.append(item)
    repair=report['repair'];total=len(repair)
    success=[r for r in repair if r['source']=='AI']
    classes=['管道维修','水电维修','家电维修','其他维修']
    priorities=['NORMAL','HIGH','URGENT']
    def matrix(field,labels):
        out={label:{pred:0 for pred in labels+['NO_PREDICTION']} for label in labels}
        for r in repair:
            pred=r.get(field) if r['source']=='AI' else None
            out[r['reference_'+field]][pred if pred in labels else 'NO_PREDICTION']+=1
        return out
    turns=[t for s in report['support'] for t in s['turns']]
    ai_turns=[t for t in turns if (t.get('metadata') or {}).get('source')=='AI']
    traces=[]
    if (folder/'provider-trace.jsonl').exists():
        traces=[json.loads(line) for line in (folder/'provider-trace.jsonl').read_text(encoding='utf-8').splitlines()]
    summary=dict(run=report['run'],model=report['model'],execution_result=report['result'],cleanup=report['cleanup'],reference_sha256=manifest['reference_sha256'],complete_support=len(report['support'])==len(manifest['scenarios']),complete_repair=len(repair)==len(manifest['repair_ids']),support=dict(scenarios=len(support),overall=dict(collections.Counter(s['overall'] for s in support)),turns=len(turns),ai_turns=len(ai_turns),sources=dict(collections.Counter((t.get('metadata') or {}).get('source','ERROR') for t in turns)),errors=dict(collections.Counter((t.get('metadata') or {}).get('errorCode') or t.get('error') for t in turns if (t.get('metadata') or {}).get('source')!='AI')),ai_reply_p95_ms=p95([t['elapsed_ms'] for t in ai_turns]),all_reply_p95_ms=p95([t['elapsed_ms'] for t in turns])),repair=dict(total=total,ai_success=len(success),sources=dict(collections.Counter(r['source'] for r in repair)),category_match_count=sum(r['category_match'] for r in repair),category_agreement_pct=pct(sum(r['category_match'] for r in repair),total),priority_match_count=sum(r['priority_match'] for r in repair),priority_agreement_pct=pct(sum(r['priority_match'] for r in repair),total),joint_agreement_pct=pct(sum(r['category_match'] and r['priority_match'] for r in repair),total),fallback_pct=pct(sum(r['source']=='MANUAL' for r in repair),total),error_pct=pct(sum(r['source']=='ERROR' for r in repair),total),all_api_p95_ms=p95([r['elapsed_ms'] for r in repair]),successful_api_p95_ms=p95([r['elapsed_ms'] for r in success]),category_matrix=matrix('category',classes),priority_matrix=matrix('priority',priorities),priority_recall={p:dict(reference_count=sum(r['reference_priority']==p for r in repair),correct=sum(r['reference_priority']==p and r['priority_match'] for r in repair),recall_pct=pct(sum(r['reference_priority']==p and r['priority_match'] for r in repair),sum(r['reference_priority']==p for r in repair))) for p in priorities}),provider=dict(recorded_calls=len(traces),http_statuses=dict(collections.Counter(str(t['status']) for t in traces)),upstream_p95_ms=p95([t['elapsed_ms'] for t in traces]),total_tokens=sum((t.get('usage') or {}).get('total_tokens',0) for t in traces)),limits='AI-generated reference and AI semantic review; not independent human accuracy. API-only isolated test with synthetic fixtures. Pacing excluded from API latency; fallback retained in denominators.')
    if summary['support']['overall'].get('PENDING'):
        summary['acceptance']='PENDING_REVIEW'
    elif summary['support']['overall'].get('FAIL'):
        summary['acceptance']='SUPPORT_HAS_FAILURES'
    else:summary['acceptance']='SUPPORT_SCENARIOS_PASSED_REPAIR_METRICS_RECORDED'
    if not summary['complete_support'] or not summary['complete_repair'] or report['cleanup']!='PASS':summary['acceptance']='INCOMPLETE'
    summary['execution_error']=report.get('error')
    summary['repair_error_codes_available']=all('error_code' in r for r in repair)
    summary['recovery_note']='Original execution status preserved. Complete request results can be scored independently of postprocessing; missing database error codes are not reconstructed as facts.'
    summary['provider']['relay_transport_errors']=sum((t.get('error') or {}).get('code')=='RELAY_UPSTREAM_ERROR' for t in traces if isinstance(t.get('error'),dict))
    summary['provider']['status_note']='502 with RELAY_UPSTREAM_ERROR is generated by the local relay on transport failure; it is not proof of an upstream HTTP 502. Token totals cover responses with usage only.'
    summary['repair']['successful_only_category_agreement_pct']=pct(sum(r['category_match'] for r in success),len(success))
    summary['repair']['successful_only_priority_agreement_pct']=pct(sum(r['priority_match'] for r in success),len(success))
    (folder/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (folder/'support-reviewed.json').write_text(json.dumps(support,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    if repair:
        fields=['id','description','reference_category','reference_priority','source','category','priority','category_match','priority_match','elapsed_ms','error_code','summary']
        with (folder/'repair-results.csv').open('w',encoding='utf-8-sig',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');writer.writeheader();writer.writerows(repair)
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
