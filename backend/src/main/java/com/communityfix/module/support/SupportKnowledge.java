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
 @Transactional public Object save(CurrentUser u,Long id,SupportController.Knowledge b){
  access.managerOnly(u);if(!access.manages(u,b.communityId()))throw ApiException.forbidden();
  if(id!=null){var old=db.required("SELECT * FROM support_knowledge WHERE id=? FOR UPDATE",id);if(!access.manages(u,(Long)old.get("communityId")))throw ApiException.missing();if(Db.integer(old,"version")!=b.version())throw ApiException.conflict();}
  if(b.communityId()!=null&&db.count("SELECT COUNT(*) FROM community WHERE id=? AND status='ENABLED'",b.communityId())==0)throw ApiException.bad("请选择有效小区");
  if(id==null)id=db.insert("support_knowledge",Db.fields("community_id",b.communityId(),"title",b.title().trim(),"keywords",b.keywords(),"question",b.question().trim(),"answer",b.answer().trim(),"audience",b.audience(),"status",b.status(),"updated_by",u.id()));
  else db.execute("UPDATE support_knowledge SET community_id=?,title=?,keywords=?,question=?,answer=?,audience=?,status=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",b.communityId(),b.title().trim(),b.keywords(),b.question().trim(),b.answer().trim(),b.audience(),b.status(),u.id(),id);
  access.audit(u,"SUPPORT_KNOWLEDGE_"+b.status(),id);return db.required("SELECT * FROM support_knowledge WHERE id=?",id);
 }
}
