package com.communityfix.module.support;

import com.communityfix.common.*;
import com.communityfix.security.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SupportAccess {
 private final Db db;
 public SupportAccess(Db db){this.db=db;}
 public boolean manager(CurrentUser u){return u.hasRole("ADMIN")||u.hasRole("SUPER_ADMIN");}
 public void managerOnly(CurrentUser u){if(!manager(u))throw ApiException.forbidden();}
 public boolean manages(CurrentUser u,Long community){return u.hasRole("SUPER_ADMIN")||u.hasRole("ADMIN")&&community!=null&&db.count("SELECT COUNT(*) FROM admin_community WHERE user_id=? AND community_id=?",u.id(),community)>0;}
 public List<Map<String,Object>> communities(CurrentUser u){
  if(u.hasRole("SUPER_ADMIN"))return db.rows("SELECT id,name FROM community WHERE status='ENABLED'");
  if(u.hasRole("ADMIN"))return db.rows("SELECT c.id,c.name FROM community c JOIN admin_community a ON a.community_id=c.id WHERE a.user_id=? AND c.status='ENABLED'",u.id());
  return db.rows("SELECT DISTINCT c.id,c.name FROM community c WHERE c.status='ENABLED' AND (EXISTS(SELECT 1 FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit un ON un.id=r.unit_id JOIN building b ON b.id=un.building_id WHERE br.user_id=? AND br.status IN ('PENDING','APPROVED') AND b.community_id=c.id) OR EXISTS(SELECT 1 FROM worker_profile w LEFT JOIN worker_service_community wc ON wc.worker_id=w.id WHERE w.user_id=? AND (wc.community_id=c.id OR w.owner_community_id=c.id)))",u.id(),u.id());
 }
 public void clientCommunity(CurrentUser u,Long community){if(community!=null&&communities(u).stream().noneMatch(c->Db.id(c,"id")==community))throw ApiException.missing();}
 public Map<String,Object> session(CurrentUser u,long id,boolean lock){
  var s=db.required("SELECT * FROM support_session WHERE id=?"+(lock?" FOR UPDATE":""),id);
  Long community=(Long)s.get("communityId");
  if(manager(u)){if(!manages(u,community))throw ApiException.missing();}
  else {if(Db.id(s,"userId")!=u.id()||!u.roles().contains(s.get("userRole")))throw ApiException.missing();clientCommunity(u,community);}
  return s;
 }
 public Map<String,Object> order(CurrentUser u,long id){
  var o=db.required("SELECT id,order_no,community_id,resident_id,worker_id,status,priority,created_at,updated_at FROM repair_order WHERE id=?",id);
  if(manager(u)){if(!manages(u,Db.id(o,"communityId")))throw ApiException.missing();}
  else if(!(u.hasRole("RESIDENT")&&Db.id(o,"residentId")==u.id())&&!(u.hasRole("WORKER")&&u.workerId()!=null&&Objects.equals(u.workerId(),o.get("workerId"))))throw ApiException.missing();
  if(!manager(u))clientCommunity(u,Db.id(o,"communityId"));
  o.remove("residentId");o.remove("workerId");return o;
 }
 public void audit(CurrentUser u,String action,long id){db.insert("sys_operation_log",Db.fields("operator_id",u.id(),"action",action,"target_type","support","target_id",id,"result","SUCCESS","request_id",Optional.ofNullable(org.slf4j.MDC.get("requestId")).orElseGet(()->UUID.randomUUID().toString())));}
}
