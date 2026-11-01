"""Isolated two-instance support acceptance; --ui keeps disposable browser fixtures alive.
Never reads deploy/.env. Create OUTPUT/stop to close UI and clean up.
"""
import argparse, concurrent.futures, functools, http.server, importlib.util, json, os
from pathlib import Path
import secrets, subprocess, threading, time, urllib.request, urllib.error, uuid
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('acceptance',ROOT/'deploy/acceptance.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--ui',action='store_true');p.add_argument('--output-dir',type=Path,default=ROOT/'.local/support-acceptance');args=p.parse_args()
 folder=args.output_dir.resolve();folder.mkdir(parents=True,exist_ok=True);stop=folder/'stop'
 if stop.exists():stop.unlink()
 db=a.module('backup','backup-local.py').Database('communityfix-mysql-1')
 suffix=uuid.uuid4().hex;schema='cf_support_'+suffix;dbuser='cf_support_'+suffix[:16];secret=secrets.token_urlsafe(32)
 env=a.clean_env();env.update(DB_URL=f'jdbc:mysql://127.0.0.1:3307/{schema}?connectionTimeZone=UTC&characterEncoding=UTF-8',DB_USER=dbuser,DB_PASSWORD=secret,JWT_SECRET=secrets.token_urlsafe(48),BOOTSTRAP_USERNAME='admin',BOOTSTRAP_PASSWORD='QAOnly_20261030!',DEMO_ENABLED='true',STORAGE_MODE='local',STORAGE_DIRECTORY=str(folder/'uploads'),EUREKA_ENABLED='false')
 processes={};logs=[];servers=[];created=False;granted=False;report={'result':'FAIL'}
 def api(port,path,token=None,data=None):
  headers={'Content-Type':'application/json'}
  if token:headers['Authorization']='Bearer '+token
  req=urllib.request.Request(f'http://127.0.0.1:{port}/api'+path,headers=headers,data=None if data is None else json.dumps(data).encode())
  with urllib.request.urlopen(req,timeout=20) as r:return json.load(r)['data']
 def login(name,port=18084):return api(port,'/auth/login',data={'username':name,'password':'QAOnly_20261030!'})['token']
 def start(port):
  log=(folder/f'backend-{port}.log').open('ab');logs.append(log)
  pe=dict(env,SERVER_PORT=str(port));processes[port]=subprocess.Popen(['java','-Xmx384m','-jar',str(ROOT/'backend/target/communityfix-backend-0.1.0.jar')],cwd=ROOT,env=pe,stdout=log,stderr=subprocess.STDOUT)
  for _ in range(100):
   if processes[port].poll() is not None:raise RuntimeError(f'Backend {port} exited; see log')
   try:
    if urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=1).status==200:return
   except Exception:time.sleep(.5)
  raise RuntimeError('Startup timeout')
 def terminate(port):
  proc=processes.get(port)
  if proc and proc.poll() is None:proc.terminate();proc.wait(timeout=15)
 try:
  with (folder/'build.log').open('wb') as log:subprocess.run(['mvn.cmd' if os.name=='nt' else 'mvn','-B','-Dmaven.repo.local='+str(ROOT/'.local/m2'),'-f','backend/pom.xml','-DskipTests','package'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
  db.query(None,f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4');created=True
  db.query(None,f"CREATE USER '{dbuser}'@'%' IDENTIFIED BY '{secret}'");granted=True
  db.query(None,f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{dbuser}'@'%'")
  start(18084);start(18085);root=login('admin');api(18084,'/support/heartbeat',root,{'online':True})
  # Synthetic fixtures reuse the disposable demo hash; no developer database is touched.
  for i in range(100):
   db.query(schema,f"INSERT INTO sys_user(username,password,nickname) SELECT 'load_{i}',password,'客服压测{i}' FROM sys_user WHERE username='resident'; INSERT INTO sys_user_role(user_id,role_id) SELECT u.id,r.id FROM sys_user u JOIN sys_role r ON r.role_code='RESIDENT' WHERE u.username='load_{i}';")
  def setup(i):
   token=login('load_'+str(i));s=api(18084,'/support/sessions',token,{'role':'RESIDENT'});s=api(18085,f"/support/sessions/{s['id']}/handoff",token,{'version':s['version']});s=api(18084,f"/support/sessions/{s['id']}/claim",root,{'version':s['version']});return token,s
  with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:clients=list(pool.map(setup,range(100)))
  latencies=[];errors=[]
  with concurrent.futures.ThreadPoolExecutor(max_workers=200) as pool:
   for batch in range(3):
    api(18084,'/support/heartbeat',root,{'online':True})
    pending=[]
    for token,s in clients:
     snap=api(18084,f"/support/sessions/{s['id']}/events?wait=false",token)
     def receive(t=token,session=s,cursor=snap['cursor']):
      result=api(18085,f"/support/sessions/{session['id']}/events?after={cursor}",t);return time.perf_counter(),result
     pending.append(pool.submit(receive))
    time.sleep(.5)
    def send(pair):
     _,s=pair;started=time.perf_counter();api(18084,f"/support/sessions/{s['id']}/messages",root,{'clientId':str(uuid.uuid4()),'content':f'人工消息验收 {batch}'});return started
    starts=list(pool.map(send,clients))
    for started,f in zip(starts,pending):
     try:
      ended,result=f.result();assert any(m['senderType']=='AGENT' for m in result['messages']);latencies.append((ended-started)*1000)
     except Exception as e:errors.append(type(e).__name__)
  latencies.sort();p95=latencies[int(len(latencies)*.95)-1] if latencies else None
  report.update(clients=100,instances=2,samples=len(latencies),human_delivery_p95_ms=round(p95,2) if p95 is not None else None,errors=errors)
  token,s=clients[0];terminate(18084);started=time.perf_counter();api(18085,'/support/heartbeat',root,{'online':True});api(18085,f"/support/sessions/{s['id']}/messages",root,{'clientId':str(uuid.uuid4()),'content':'存活实例继续接待'});assert '存活实例继续接待' in json.dumps(api(18085,f"/support/sessions/{s['id']}/events?wait=false",token),ensure_ascii=False);report['surviving_instance_ms']=round((time.perf_counter()-started)*1000,2)
  started=time.perf_counter();start(18084);assert api(18084,f"/support/sessions/{s['id']}",token)['session']['id']==s['id'];report['restart_seconds']=round(time.perf_counter()-started,2)
  report['result']='PASS' if p95 is not None and p95<2000 and not errors else 'FAIL';a.save(folder/'report.json',report);print(json.dumps(report),flush=True)
  if args.ui:
   class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
     if self.path.startswith('/api/'):return self.proxy()
     if not Path(self.translate_path(self.path.split('?')[0])).is_file():self.path='/index.html'
     super().do_GET()
    def do_POST(self):self.proxy()
    def do_PATCH(self):self.proxy()
    def proxy(self):
     data=self.rfile.read(int(self.headers.get('Content-Length',0)));headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','origin','connection')};req=urllib.request.Request('http://127.0.0.1:18084'+self.path,data=data or None,headers=headers,method=self.command)
     try:r=urllib.request.urlopen(req,timeout=20)
     except urllib.error.HTTPError as e:r=e
     except (urllib.error.URLError,OSError):return
     body=r.read();self.send_response(r.status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers()
     try:self.wfile.write(body)
     except OSError:pass
   for port,directory in [(18093,'admin-web/dist'),(18094,'miniapp/dist/build/h5')]:
    srv=http.server.ThreadingHTTPServer(('127.0.0.1',port),functools.partial(Handler,directory=str(ROOT/directory)));servers.append(srv);threading.Thread(target=srv.serve_forever,daemon=True).start()
   print('UI READY 18093/18094; create '+str(stop)+' to clean up',flush=True)
   while not stop.exists():time.sleep(1)
 finally:
  for srv in servers:srv.shutdown()
  for port in list(processes):terminate(port)
  for log in logs:log.close()
  if granted:db.query(None,f"DROP USER '{dbuser}'@'%'")
  if created:db.query(None,f'DROP DATABASE `{schema}`')
  report['cleanup']='PASS';a.save(folder/'report.json',report);print('CLEANUP PASS',flush=True)
 if report['result']!='PASS':raise SystemExit(1)
if __name__=='__main__':main()
