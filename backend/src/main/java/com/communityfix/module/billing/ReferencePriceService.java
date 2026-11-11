package com.communityfix.module.billing;
import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ReferencePriceService {
 private final Db db;public ReferencePriceService(Db db){this.db=db;}
 private void access(long community){var u=CurrentUser.get();u.role("ADMIN");u.community(community);u.permission("repair:assign");}
 public Object list(long community){access(community);return db.rows("SELECT c.id AS category_id,c.name AS category_name,p.community_id,p.labor_fen,p.material_fen,p.basis,p.updated_by,p.updated_at,p.version,p.enabled FROM repair_category c LEFT JOIN repair_reference_price p ON p.category_id=c.id AND p.community_id=? WHERE c.status='ENABLED' ORDER BY c.sort_order,c.id",community);}
 public Object history(long community,long category){access(community);return db.rows("SELECT * FROM repair_reference_price_history WHERE community_id=? AND category_id=? ORDER BY id DESC LIMIT 50",community,category);}
 @Transactional public void save(long community,long category,BillingController.ReferencePrice b){
  access(community);db.required("SELECT id FROM community WHERE id=? AND status='ENABLED' FOR UPDATE",community);
  if(db.count("SELECT COUNT(*) FROM repair_category WHERE id=? AND status='ENABLED'",category)!=1)throw ApiException.bad("分类不可用");
  long total=Math.addExact(b.laborFen(),b.materialFen());if(total<=0||total>10000000)throw ApiException.bad("参考总价须为0.01至100000元");
  var old=db.row("SELECT version FROM repair_reference_price WHERE community_id=? AND category_id=? FOR UPDATE",community,category);
  int expected=old==null?-1:Db.integer(old,"version");if(b.version()==null?old!=null:b.version()!=expected)throw ApiException.conflict();
  int version=expected+1;boolean enabled=b.enabled()==null||b.enabled();
  if(old==null)db.execute("INSERT INTO repair_reference_price(community_id,category_id,labor_fen,material_fen,basis,updated_by,version,enabled) VALUES (?,?,?,?,?,?,?,?)",community,category,b.laborFen(),b.materialFen(),b.basis(),CurrentUser.get().id(),version,enabled);
  else db.execute("UPDATE repair_reference_price SET labor_fen=?,material_fen=?,basis=?,updated_by=?,version=?,enabled=?,updated_at=UTC_TIMESTAMP(3) WHERE community_id=? AND category_id=?",b.laborFen(),b.materialFen(),b.basis(),CurrentUser.get().id(),version,enabled,community,category);
  db.insert("repair_reference_price_history",Db.fields("community_id",community,"category_id",category,"labor_fen",b.laborFen(),"material_fen",b.materialFen(),"basis",b.basis(),"updated_by",CurrentUser.get().id(),"version",version,"enabled",enabled));
 }
}
