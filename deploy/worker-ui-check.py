"""Disposable local UI check server; never reads deploy/.env. Ctrl+C cleans up."""
import functools
import http.server
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.request
import urllib.error
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('acceptance',ROOT/'deploy/acceptance.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
db=a.module('backup','backup-local.py').Database('communityfix-mysql-1')
folder=ROOT/'.local'/'worker-ui'
folder.mkdir(exist_ok=True)
stop=folder/'stop'
if stop.exists():stop.unlink()
schema='cf_ui_'+uuid.uuid4().hex
user='cf_ui_'+uuid.uuid4().hex[:20]
password=secrets.token_urlsafe(32)
env=a.clean_env()
env.update(DB_URL=f'jdbc:mysql://127.0.0.1:3307/{schema}?connectionTimeZone=UTC&characterEncoding=UTF-8',DB_USER=user,DB_PASSWORD=password,JWT_SECRET=secrets.token_urlsafe(48),BOOTSTRAP_USERNAME='admin',BOOTSTRAP_PASSWORD='QAOnly_20261029!',DEMO_ENABLED='true',STORAGE_MODE='local',STORAGE_DIRECTORY=str(folder/'uploads'),EUREKA_ENABLED='false',SERVER_PORT='18084')
class Handler(http.server.SimpleHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  if self.path.startswith('/api/'):return self.api()
  path=self.translate_path(self.path.split('?')[0])
  if not Path(path).is_file():self.path='/index.html'
  super().do_GET()
 def do_POST(self):self.api()
 def do_PATCH(self):self.api()
 def do_DELETE(self):self.api()
 def api(self):
  data=self.rfile.read(int(self.headers.get('Content-Length',0)))
  headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','origin','connection')}
  req=urllib.request.Request('http://127.0.0.1:18084'+self.path,data=data if data else None,headers=headers,method=self.command)
  try:r=urllib.request.urlopen(req,timeout=20)
  except urllib.error.HTTPError as e:r=e
  self.send_response(r.status)
  for k,v in r.headers.items():
   if k.lower() not in ('transfer-encoding','connection','content-length'):self.send_header(k,v)
  body=r.read();self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
process=None;servers=[];created=False;granted=False
try:
 db.query(None,f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4');created=True
 db.query(None,f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'");granted=True
 db.query(None,f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{user}'@'%'")
 with (folder/'backend.log').open('wb') as log:
  process=subprocess.Popen(['mvn.cmd','-B','-Dmaven.repo.local='+str(ROOT/'.local/m2'),'-f','backend/pom.xml','spring-boot:run'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
  for _ in range(120):
   try:
    if urllib.request.urlopen('http://127.0.0.1:18084/actuator/health',timeout=1).status==200:break
   except Exception:time.sleep(1)
  else:raise RuntimeError('Backend not ready')
  for port,directory in [(18094,'miniapp/dist/build/h5'),(18093,'admin-web/dist')]:
   server=http.server.ThreadingHTTPServer(('127.0.0.1',port),functools.partial(Handler,directory=str(ROOT/directory)))
   servers.append(server);threading.Thread(target=server.serve_forever,daemon=True).start()
  print('READY: H5 18094, admin 18093; stop with .local/worker-ui/stop',flush=True)
  while not stop.exists() and process.poll() is None:time.sleep(1)
finally:
 for server in servers:server.shutdown()
 if process and process.poll() is None:subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if granted:db.query(None,f"DROP USER '{user}'@'%'")
 if created:db.query(None,f'DROP DATABASE `{schema}`')
 print('UI database cleanup complete',flush=True)
