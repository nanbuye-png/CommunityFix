package com.communityfix.module.support;

import com.communityfix.common.*;
import com.communityfix.module.ai.AiGuard;
import com.communityfix.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SupportService {
 private final Db db; private final SupportAccess access; private final ObjectMapper json; private final AiGuard guard;
 public SupportService(Db db,SupportAccess access,ObjectMapper json,AiGuard guard){this.db=db;this.access=access;this.json=json;this.guard=guard;}
 public Object options(CurrentUser u){return Db.fields("communities",access.communities(u),"manager",access.manager(u));}
 @Transactional public Object create(CurrentUser u,Long community,Long order,String role){
  if(!Set.of("RESIDENT","WORKER").contains(role)||!u.hasRole(role))throw ApiException.forbidden();
  if(order!=null)community=Db.id(access.order(u,order),"communityId");access.clientCommunity(u,community);
  long id=db.insert("support_session",Db.fields("user_id",u.id(),"user_role",role,"community_id",community,"order_id",order));
  var s=access.session(u,id,true);append(s,null,"SYSTEM",null,"你好，可以咨询平台使用问题或关联工单。需要帮助时可随时转人工。",null);
  return access.session(u,id,false);
 }
 public Object list(CurrentUser u,String status,boolean mine,int page){
  List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
  if(!access.manager(u)){where+=" AND s.user_id=?";args.add(u.id());}
  else if(!u.hasRole("SUPER_ADMIN")){where+=" AND EXISTS(SELECT 1 FROM admin_community ac WHERE ac.community_id=s.community_id AND ac.user_id=?)";args.add(u.id());}
  if(status!=null&&!status.isBlank()){if(!Set.of("AI","WAITING","HUMAN","CLOSED").contains(status))throw ApiException.bad("会话状态无效");where+=" AND s.status=?";args.add(status);}
  if(mine&&access.manager(u)){where+=" AND s.agent_id=?";args.add(u.id());}
  long total=db.count("SELECT COUNT(*) FROM support_session s"+where,args.toArray());
  var selectArgs=new ArrayList<Object>();selectArgs.add(u.id());selectArgs.addAll(args);selectArgs.add(Pages.offset(page,20));
  var rows=db.rows("SELECT s.*,u.nickname,c.name AS community_name,(SELECT COUNT(*) FROM support_message m WHERE m.session_id=s.id AND (m.sender_id IS NULL OR m.sender_id<>?) AND m.seq>COALESCE((SELECT last_seq FROM support_read r WHERE r.session_id=s.id AND r.user_id="+u.id()+"),0)) AS unread FROM support_session s JOIN sys_user u ON u.id=s.user_id LEFT JOIN community c ON c.id=s.community_id"+where+" ORDER BY s.updated_at DESC,s.id DESC LIMIT 20 OFFSET ?",selectArgs.toArray());
  // Relations can be revoked after a conversation was created. Do not expose its preview.
  if(!access.manager(u))rows=rows.stream().filter(s->{try{access.session(u,Db.id(s,"id"),false);return true;}catch(ApiException e){return false;}}).toList();
  return Pages.result(rows,total,page,20);
 }
 public Object detail(CurrentUser u,long id){var s=access.session(u,id,false);return Db.fields("session",s,"order",card(u,s),"actions",actions(u,s));}
 public Object events(CurrentUser u,long id,long after){
  var s=access.session(u,id,false);var messages=db.rows("SELECT * FROM support_message WHERE session_id=? AND seq>? ORDER BY seq LIMIT 100",id,after);
  for(var m:messages){Object raw=m.remove("metadataJson");if(raw!=null)try{m.put("metadata",json.readTree(raw.toString()));}catch(Exception ignored){}}
  long cursor=messages.size()==100?Db.id(messages.getLast(),"seq"):Db.id(s,"eventSeq");
  return Db.fields("session",s,"messages",messages,"cursor",cursor,"order",card(u,s),"actions",actions(u,s));
 }
 private Object card(CurrentUser u,Map<String,Object>s){if(s.get("orderId")==null)return null;try{return access.order(u,Db.id(s,"orderId"));}catch(ApiException e){return null;}}
 private Object actions(CurrentUser u,Map<String,Object>s){var actions=new ArrayList<String>();if(!access.manager(u)){actions.add("HUMAN");if(u.hasRole("RESIDENT")){actions.add("CREATE_REPAIR");actions.add("BIND_ROOM");}if(card(u,s)!=null)actions.add("ORDER_DETAIL");}return actions;}
 @Transactional public Object send(CurrentUser u,long id,String clientId,String text){
  var s=access.session(u,id,true);var old=db.row("SELECT id,seq,content FROM support_message WHERE session_id=? AND sender_id=? AND client_id=?",id,u.id(),clientId);
  if(old!=null){if(!text.trim().equals(old.get("content")))throw ApiException.conflict();return old;}
  if("CLOSED".equals(s.get("status")))throw ApiException.bad("会话已结束，请新建咨询");
  if(access.manager(u)&&(!"HUMAN".equals(s.get("status"))||!Objects.equals(s.get("agentId"),u.id())))throw ApiException.forbidden();
  if(access.manager(u)&&db.count("SELECT COUNT(*) FROM support_agent WHERE user_id=? AND online=1 AND heartbeat_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 90 SECOND)",u.id())==0)throw ApiException.bad("请先上线接待");
  if(!access.manager(u)&&"AI".equals(s.get("status")))guard.reserveRequest(u.id());
  long message=append(s,u.id(),access.manager(u)?"AGENT":"USER",clientId,text.trim(),null);
  if(!access.manager(u)&&"AI".equals(s.get("status")))db.insert("support_ai_job",Db.fields("session_id",id,"message_id",message,"generation",s.get("aiGeneration")));
  if(access.manager(u))notifyUser(s,"reply:"+message,"客服回复了你的咨询");
  return db.required("SELECT id,seq,content FROM support_message WHERE id=?",message);
 }
 @Transactional public Object action(CurrentUser u,long id,String action,int version){
  var s=access.session(u,id,true);if(Db.integer(s,"version")!=version)throw ApiException.conflict();
  String state=(String)s.get("status");boolean manager=access.manager(u);Object agent=s.get("agentId");String next;
  switch(action){
   case "handoff" -> {if(manager||!state.equals("AI"))throw ApiException.conflict();next="WAITING";agent=null;}
   case "cancel-wait" -> {if(manager||!state.equals("WAITING"))throw ApiException.conflict();next="AI";agent=null;}
   case "claim" -> {access.managerOnly(u);if(!state.equals("WAITING"))throw ApiException.conflict();onlineRequired(u);next="HUMAN";agent=u.id();}
   case "takeover" -> {u.role("SUPER_ADMIN");if(!state.equals("HUMAN")&&!state.equals("WAITING"))throw ApiException.conflict();onlineRequired(u);next="HUMAN";agent=u.id();}
   case "close" -> {if(state.equals("CLOSED"))throw ApiException.conflict();if(manager&&!u.hasRole("SUPER_ADMIN")&&!Objects.equals(agent,u.id()))throw ApiException.forbidden();next="CLOSED";}
   default -> throw ApiException.bad("无效操作");
  }
  db.execute("UPDATE support_session SET status=?,agent_id=?,version=version+1,ai_generation=ai_generation+1,closed_at=IF(?='CLOSED',UTC_TIMESTAMP(3),NULL) WHERE id=?",next,agent,next,id);
  String text=switch(next){case "WAITING"->"已转人工，正在等待接入；可以继续留言。";case "HUMAN"->"人工客服已接入。";case "CLOSED"->"本次咨询已结束。";default->"已恢复 AI 客服。";};
  append(s,null,"SYSTEM",null,text,null);access.audit(u,"SUPPORT_"+action.toUpperCase(Locale.ROOT),id);
  if(next.equals("HUMAN")||next.equals("CLOSED"))notifyUser(s,action+":"+(version+1),text);
  return access.session(u,id,false);
 }
 private void onlineRequired(CurrentUser u){if(db.count("SELECT COUNT(*) FROM support_agent WHERE user_id=? AND online=1 AND heartbeat_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 90 SECOND)",u.id())==0)throw ApiException.bad("请先开启在线接待");}
 @Transactional public void heartbeat(CurrentUser u,boolean online){access.managerOnly(u);db.execute("INSERT INTO support_agent(user_id,online,heartbeat_at) VALUES (?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE online=VALUES(online),heartbeat_at=UTC_TIMESTAMP(3)",u.id(),online);}
 @Transactional public void read(CurrentUser u,long id,long seq){var s=access.session(u,id,false);long safe=Math.max(0,Math.min(seq,Db.id(s,"eventSeq")));db.execute("INSERT INTO support_read(session_id,user_id,last_seq) VALUES (?,?,?) ON DUPLICATE KEY UPDATE last_seq=GREATEST(last_seq,VALUES(last_seq))",id,u.id(),safe);}
 public long append(Map<String,Object>s,Long sender,String type,String client,String text,Object metadata){
  long id=Db.id(s,"id");db.execute("UPDATE support_session SET event_seq=event_seq+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);
  long seq=Db.id(db.required("SELECT event_seq FROM support_session WHERE id=?",id),"eventSeq");
  String encoded=null;try{if(metadata!=null)encoded=json.writeValueAsString(metadata);}catch(Exception e){throw new IllegalArgumentException(e);}
  return db.insert("support_message",Db.fields("session_id",id,"seq",seq,"sender_id",sender,"sender_type",type,"client_id",client,"content",text,"metadata_json",encoded));
 }
 private void notifyUser(Map<String,Object>s,String key,String text){db.execute("INSERT INTO notification(user_id,event_key,title,content,support_session_id) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",s.get("userId"),"support:"+s.get("id")+":"+key,"客服消息",text,s.get("id"));}
 @Transactional public void maintain(){
  var rows=db.rows("SELECT s.* FROM support_session s WHERE (s.status='HUMAN' AND (NOT EXISTS(SELECT 1 FROM support_agent a JOIN sys_user u ON u.id=a.user_id WHERE a.user_id=s.agent_id AND a.online=1 AND u.status='ENABLED' AND a.heartbeat_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 90 SECOND)) OR NOT EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=s.agent_id AND (r.role_code='SUPER_ADMIN' OR r.role_code='ADMIN' AND EXISTS(SELECT 1 FROM admin_community ac WHERE ac.user_id=s.agent_id AND ac.community_id=s.community_id))))) OR (s.status<>'CLOSED' AND s.updated_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 7 DAY)) LIMIT 50 FOR UPDATE SKIP LOCKED");
  for(var s:rows){boolean idle=((java.time.Instant)s.get("updatedAt")).isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(7)));db.execute("UPDATE support_session SET status=?,agent_id=NULL,version=version+1,ai_generation=ai_generation+1,closed_at=IF(?,UTC_TIMESTAMP(3),NULL) WHERE id=?",idle?"CLOSED":"WAITING",idle,s.get("id"));append(s,null,"SYSTEM",null,idle?"长时间未活动，会话已结束。":"客服暂时离线或权限发生变化，已返回等待队列。",null);if(idle)notifyUser(s,"idle","咨询已自动结束");}
  db.execute("DELETE FROM support_session WHERE status='CLOSED' AND closed_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 90 DAY) LIMIT 100");
 }
}
