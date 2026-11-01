package com.communityfix.module.support;

import com.communityfix.common.*;
import com.communityfix.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SupportJobs {
 private final Db db;private final SupportService service;private final SupportAccess access;private final IdentityService identities;
 public SupportJobs(Db db,SupportService service,SupportAccess access,IdentityService identities){this.db=db;this.service=service;this.access=access;this.identities=identities;}
 @Transactional public Map<String,Object> claim(){
  var job=db.row("SELECT j.* FROM support_ai_job j WHERE (j.status='PENDING' OR j.status='PROCESSING' AND j.locked_until<UTC_TIMESTAMP(3)) AND NOT EXISTS(SELECT 1 FROM support_ai_job earlier WHERE earlier.session_id=j.session_id AND earlier.id<j.id AND earlier.status IN ('PENDING','PROCESSING')) ORDER BY j.id LIMIT 1 FOR UPDATE SKIP LOCKED");
  if(job==null)return null;String lease=UUID.randomUUID().toString();db.execute("UPDATE support_ai_job SET status='PROCESSING',lease_token=?,locked_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 45 SECOND),attempts=attempts+1 WHERE id=?",lease,job.get("id"));job.put("leaseToken",lease);return job;
 }
 public CurrentUser owner(Map<String,Object>s){var row=db.required("SELECT token_version FROM sys_user WHERE id=?",s.get("userId"));return identities.load(Db.id(s,"userId"),Db.integer(row,"tokenVersion"));}
 public Map<String,Object> session(Map<String,Object>job){var s=db.required("SELECT * FROM support_session WHERE id=?",job.get("sessionId"));access.session(owner(s),Db.id(s,"id"),false);return s;}
 @Transactional public void finish(Map<String,Object>job,String content,Object metadata,String error,long duration){
  // Lock order matches user writes: session before job. Fence old workers after lease takeover.
  var s=db.row("SELECT * FROM support_session WHERE id=? FOR UPDATE",job.get("sessionId"));if(s==null)return;
  var current=db.required("SELECT * FROM support_ai_job WHERE id=? FOR UPDATE",job.get("id"));if(!Objects.equals(current.get("leaseToken"),job.get("leaseToken"))||!"PROCESSING".equals(current.get("status")))return;
  boolean valid="AI".equals(s.get("status"))&&Objects.equals(s.get("aiGeneration"),job.get("generation"));
  try{access.session(owner(s),Db.id(s,"id"),false);}catch(ApiException e){valid=false;}
  if(valid)service.append(s,null,"AI",null,content,metadata);
  db.execute("UPDATE support_ai_job SET status=?,locked_until=NULL,duration_ms=?,error_code=? WHERE id=?",valid?"DONE":"CANCELED",duration,error,job.get("id"));
 }
}
