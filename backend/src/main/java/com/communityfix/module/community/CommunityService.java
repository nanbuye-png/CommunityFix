package com.communityfix.module.community;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CommunityService {
    private final Db db;
    public CommunityService(Db db) { this.db=db; }
    public static final String ROOM_JOIN=" FROM room r JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id JOIN community c ON c.id=b.community_id ";
    public Map<String,Object> room(long id) { return db.required("SELECT r.id,r.status,c.id AS community_id,c.status AS community_status,b.status AS building_status,u.status AS unit_status"+ROOM_JOIN+"WHERE r.id=?",id); }
    public void enabled(Map<String,Object> room) { for(String key:List.of("status","communityStatus","buildingStatus","unitStatus")) if(!"ENABLED".equals(room.get(key))) throw ApiException.bad("房屋或所在小区已停用"); }
    public List<Map<String,Object>> communities() { return db.rows("SELECT id,name,address FROM community WHERE status='ENABLED' ORDER BY id"); }
    public List<Map<String,Object>> buildings(long communityId) { return db.rows("SELECT id,building_no,community_id FROM building WHERE community_id=? AND status='ENABLED' ORDER BY id",communityId); }
    public List<Map<String,Object>> rooms(long buildingId) { return db.rows("SELECT r.id,r.room_no,u.unit_no,b.building_no,c.id AS community_id,c.name AS community_name"+ROOM_JOIN+"WHERE b.id=? AND r.status='ENABLED' AND u.status='ENABLED' AND b.status='ENABLED' AND c.status='ENABLED' ORDER BY u.id,r.room_no",buildingId); }
    @Transactional public Object bind(long roomId) {
        var user=CurrentUser.get();user.role("RESIDENT");var room=room(roomId);enabled(room);
        var existing=db.row("SELECT * FROM resident_room_binding WHERE user_id=? AND room_id=? FOR UPDATE",user.id(),roomId);
        long id;String old=null;
        if(existing==null) id=db.insert("resident_room_binding",Db.fields("user_id",user.id(),"room_id",roomId));
        else { old=(String)existing.get("status"); if(!Set.of("REJECTED","REVOKED").contains(old)) throw ApiException.conflict();id=Db.id(existing,"id");db.execute("UPDATE resident_room_binding SET status='PENDING',reviewer_id=NULL,reviewed_at=NULL,review_reason=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id); }
        db.insert("resident_room_binding_log",Db.fields("binding_id",id,"old_status",old,"new_status","PENDING","operator_id",user.id(),"reason","居民申请绑定"));
        return db.required("SELECT * FROM resident_room_binding WHERE id=?",id);
    }
    public Object bindings(int page,int size) {
        var user=CurrentUser.get();String condition;List<Object> args=new ArrayList<>();
        if(user.hasRole("ADMIN")) { condition="b.community_id IN ("+String.join(",",Collections.nCopies(user.communities().size(),"?"))+")";args.addAll(user.communities()); if(args.isEmpty()) condition="1=0"; }
        else {user.role("RESIDENT");condition="br.user_id=?";args.add(user.id());}
        String from=" FROM resident_room_binding br JOIN sys_user s ON s.id=br.user_id JOIN room r ON r.id=br.room_id JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id JOIN community c ON c.id=b.community_id WHERE "+condition;
        long count=db.count("SELECT COUNT(*)"+from,args.toArray());args.add(Pages.size(size));args.add(Pages.offset(page,size));
        return Pages.result(db.rows("SELECT br.*,s.nickname,c.name AS community_name,b.building_no,u.unit_no,r.room_no"+from+" ORDER BY br.created_at DESC,br.id DESC LIMIT ? OFFSET ?",args.toArray()),count,page,size);
    }
    @Transactional public Object review(long id,boolean approved,boolean revoke,String reason) {
        var user=CurrentUser.get();user.role("ADMIN");
        var binding=db.required("SELECT * FROM resident_room_binding WHERE id=? FOR UPDATE",id);user.community(Db.id(room(Db.id(binding,"roomId")),"communityId"));
        String old=(String)binding.get("status"); if(!(revoke?"APPROVED":"PENDING").equals(old)) throw ApiException.conflict();
        String next=revoke?"REVOKED":approved?"APPROVED":"REJECTED";
        if((revoke||!approved) && (reason==null||reason.isBlank())) throw ApiException.bad("请填写审核或撤销原因");
        db.execute("UPDATE resident_room_binding SET status=?,reviewer_id=?,reviewed_at=UTC_TIMESTAMP(3),review_reason=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status=?",next,user.id(),reason,id,old);
        db.insert("resident_room_binding_log",Db.fields("binding_id",id,"old_status",old,"new_status",next,"operator_id",user.id(),"reason",reason));
        db.insert("notification",Db.fields("user_id",binding.get("userId"),"event_key","binding:"+id+":"+UUID.randomUUID(),"title","房屋绑定审核结果","content",next+" "+Objects.toString(reason,"")));
        return db.required("SELECT * FROM resident_room_binding WHERE id=?",id);
    }
}
