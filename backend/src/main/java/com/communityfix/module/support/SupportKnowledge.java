package com.communityfix.module.support;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SupportKnowledge {
 private final Db db;private final SupportAccess access;
 public SupportKnowledge(Db db,SupportAccess access){this.db=db;this.access=access;}
 public Object list(CurrentUser u,Long community,boolean editing,int page){
  if(editing){access.managerOnly(u);String where=u.hasRole("SUPER_ADMIN")?"":" WHERE EXISTS(SELECT 1 FROM admin_community a WHERE a.community_id=k.community_id AND a.user_id="+u.id()+")";return Pages.result(db.rows("SELECT k.* FROM support_knowledge k"+where+" ORDER BY updated_at DESC,id DESC LIMIT 20 OFFSET ?",Pages.offset(page,20)),db.count("SELECT COUNT(*) FROM support_knowledge k"+where),page,20);}
  access.clientCommunity(u,community);return candidates(community,u.hasRole("WORKER")?"WORKER":"RESIDENT").stream().limit(20).toList();
 }
 private List<Map<String,Object>> candidates(Long community,String role){return db.rows("SELECT id,title,keywords,question,answer,community_id FROM support_knowledge WHERE status='PUBLISHED' AND (community_id IS NULL OR community_id=?) AND audience IN ('ALL',?) ORDER BY community_id IS NULL,id DESC LIMIT 500",community,role);}
 public List<Map<String,Object>> retrieve(Long community,String role,String question){
  String text=question.toLowerCase(Locale.ROOT);
  return candidates(community,role).stream().filter(k->{String keywords=(k.get("keywords")+" "+k.get("title")+" "+k.get("question")).toLowerCase(Locale.ROOT);return Arrays.stream(keywords.split("[\\s,，、;；]+" )).anyMatch(w->w.length()>1&&text.contains(w))||((String)k.get("answer")).contains(question)||text.contains(((String)k.get("question")).toLowerCase(Locale.ROOT));}).limit(5).toList();
 }
 public List<Map<String,Object>> retrieve(Long community,String role,String question,List<String> history){
  var direct=retrieve(community,role,question);
  // A new explicit topic wins. Expand only an unresolved contextual follow-up.
  if(!direct.isEmpty()||!question.matches("(?s).*(那|这个|这些|刚才|上述|还需要|需要哪些|怎么提交|怎么办|材料|多久).*"))return direct;
  for(String previous:history){var matches=retrieve(community,role,previous);if(!matches.isEmpty())return matches;}
  return direct;
 }
 public Optional<Map<String,Object>> directAnswer(Long community,String role,String question){
  String normalized=normalize(question);
  var exact=db.rows("SELECT k.id,k.title,k.answer,k.community_id FROM support_knowledge k WHERE k.status='PUBLISHED' AND (k.community_id IS NULL OR k.community_id=?) AND k.audience IN ('ALL',?) AND (k.normalized_question=? OR EXISTS(SELECT 1 FROM support_knowledge_alias a WHERE a.knowledge_id=k.id AND a.normalized_alias=?)) ORDER BY k.community_id IS NULL,k.id DESC LIMIT 3",community,role,normalized,normalized);
  var local=exact.stream().filter(k->k.get("communityId")!=null).toList();
  var preferred=local.isEmpty()?exact:local;
  return preferred.size()==1?Optional.of(preferred.get(0)):Optional.empty();
 }
 private static String normalize(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[\\s？?！!。]+", "");}
 public Object statistics(CurrentUser u){
  access.managerOnly(u);String scope=u.hasRole("SUPER_ADMIN")?"":" AND EXISTS(SELECT 1 FROM admin_community a WHERE a.community_id=s.community_id AND a.user_id="+u.id()+")";
  return db.required("SELECT COUNT(*) AS replies,COALESCE(SUM(JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.source'))='KNOWLEDGE'),0) AS faq_replies,COALESCE(SUM(JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.modelRequested'))='true'),0) AS model_requests,COALESCE(SUM(JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.source'))='FALLBACK'),0) AS fallbacks,SUM(CASE WHEN JSON_TYPE(JSON_EXTRACT(m.metadata_json,'$.totalTokens'))='INTEGER' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.totalTokens')) AS UNSIGNED) ELSE NULL END) AS reported_tokens,COALESCE(SUM(JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.modelRequested'))='true' AND COALESCE(JSON_TYPE(JSON_EXTRACT(m.metadata_json,'$.totalTokens')),'NULL')='NULL'),0) AS unknown_usage FROM support_message m JOIN support_session s ON s.id=m.session_id WHERE m.sender_type='AI' AND m.created_at>=UTC_TIMESTAMP()-INTERVAL 30 DAY"+scope);
 }
 @Transactional public Object save(CurrentUser u,Long id,SupportController.Knowledge b){
  access.managerOnly(u);if(!access.manages(u,b.communityId()))throw ApiException.forbidden();
  if(id!=null){var old=db.required("SELECT * FROM support_knowledge WHERE id=? FOR UPDATE",id);if(!access.manages(u,(Long)old.get("communityId")))throw ApiException.missing();if(Db.integer(old,"version")!=b.version())throw ApiException.conflict();}
  if(b.communityId()!=null&&db.count("SELECT COUNT(*) FROM community WHERE id=? AND status='ENABLED'",b.communityId())==0)throw ApiException.bad("请选择有效小区");
  if(id==null)id=db.insert("support_knowledge",Db.fields("community_id",b.communityId(),"title",b.title().trim(),"keywords",b.keywords(),"question",b.question().trim(),"answer",b.answer().trim(),"audience",b.audience(),"status",b.status(),"updated_by",u.id()));
  else db.execute("UPDATE support_knowledge SET community_id=?,title=?,keywords=?,question=?,answer=?,audience=?,status=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",b.communityId(),b.title().trim(),b.keywords(),b.question().trim(),b.answer().trim(),b.audience(),b.status(),u.id(),id);
  String aliases=Objects.toString(b.aliases(),"");var lines=aliases.lines().map(String::trim).filter(a->!a.isBlank()).distinct().toList();if(lines.size()>20||lines.stream().anyMatch(a->a.length()>500))throw ApiException.bad("最多20个别名，每个不超过500字");
  db.execute("UPDATE support_knowledge SET aliases=?,normalized_question=? WHERE id=?",aliases,normalize(b.question()),id);
  db.execute("DELETE FROM support_knowledge_alias WHERE knowledge_id=?",id);
  for(String alias:lines)db.insert("support_knowledge_alias",Db.fields("knowledge_id",id,"normalized_alias",normalize(alias)));
  access.audit(u,"SUPPORT_KNOWLEDGE_"+b.status(),id);return db.required("SELECT * FROM support_knowledge WHERE id=?",id);
 }
}
