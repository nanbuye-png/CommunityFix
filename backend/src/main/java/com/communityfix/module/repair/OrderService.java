package com.communityfix.module.repair;

import com.communityfix.common.*;
import com.communityfix.module.community.CommunityService;
import com.communityfix.module.ai.AiPrivacy;
import com.communityfix.module.notification.NotificationService;
import com.communityfix.security.CurrentUser;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class OrderService {
    private final com.communityfix.module.billing.BillingPolicy billing;private final Db db;private final CommunityService communities;private final IdempotencyService idempotency;private final NotificationService notifications;private final Clock clock;
    public OrderService(Db db,CommunityService communities,IdempotencyService idempotency,NotificationService notifications,Clock clock,com.communityfix.module.billing.BillingPolicy billing){this.billing=billing;this.db=db;this.communities=communities;this.idempotency=idempotency;this.notifications=notifications;this.clock=clock;}
    private static final String VIEW="SELECT o.*,s.nickname AS resident_name,c.name AS community_name,b.building_no,u.unit_no,r.room_no,cat.name AS category_name,w.real_name AS worker_name FROM repair_order o JOIN sys_user s ON s.id=o.resident_id JOIN community c ON c.id=o.community_id JOIN room r ON r.id=o.room_id JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id JOIN repair_category cat ON cat.id=o.category_id LEFT JOIN worker_profile w ON w.id=o.worker_id";
    public Map<String,Object> read(long id){var row=db.required(VIEW+" WHERE o.id=?",id);checkRead(row);return row;}
    public void checkRead(Map<String,Object> order){
        var user=CurrentUser.get();
        user.permission("repair:view");
        boolean allowed=(user.hasRole("RESIDENT")&&Db.id(order,"residentId")==user.id())||(user.hasRole("ADMIN")&&user.communities().contains(Db.id(order,"communityId")))||(user.hasRole("WORKER")&&user.workerId()!=null&&Objects.equals(order.get("workerId"),user.workerId()));
        if(!allowed)throw ApiException.missing();
    }
    public Object list(int page,int size,String status,String search){
        var user=CurrentUser.get();List<Object> args=new ArrayList<>();List<String> scopes=new ArrayList<>();
        if(user.hasRole("RESIDENT")){scopes.add("o.resident_id=?");args.add(user.id());}
        if(user.hasRole("WORKER")&&user.workerId()!=null){scopes.add("o.worker_id=?");args.add(user.workerId());}
        if(user.hasRole("ADMIN")&&!user.communities().isEmpty()){scopes.add("o.community_id IN ("+String.join(",",Collections.nCopies(user.communities().size(),"?"))+")");args.addAll(user.communities());}
        String where=" WHERE ("+(scopes.isEmpty()?"1=0":String.join(" OR ",scopes))+")";
        if(status!=null&&!status.isBlank()){OrderStatus.valueOf(status);where+=" AND o.status=?";args.add(status);}
        if(search!=null&&!search.isBlank()){if(search.length()>100)throw ApiException.bad("搜索文字过长");where+=" AND (o.order_no LIKE ? OR o.title LIKE ?)";args.add("%"+search+"%");args.add("%"+search+"%");}
        long total=db.count("SELECT COUNT(*) FROM repair_order o"+where,args.toArray());args.add(Pages.size(size));args.add(Pages.offset(page,size));
        return Pages.result(db.rows(VIEW+where+" ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,size);
    }
    @Transactional public Object create(OrderController.Create body,String key){
        return idempotency.run("order:create",key,body,()->{
            var user=CurrentUser.get();user.role("RESIDENT");user.permission("repair:create");
            String scope=body.facilityScope()==null?"PRIVATE":body.facilityScope();
            if("PUBLIC".equals(scope))requireText(body.locationText(),200,"请填写公共设施实际位置");
            var room=communities.room(body.roomId());communities.enabled(room);
            var binding=db.row("SELECT id FROM resident_room_binding WHERE user_id=? AND room_id=? AND status='APPROVED' FOR UPDATE",user.id(),body.roomId());
            if(binding==null)throw ApiException.bad("请选择已经审核通过的房屋");
            if(db.count("SELECT COUNT(*) FROM repair_category WHERE id=? AND status='ENABLED'",body.categoryId())!=1)throw ApiException.bad("报修分类不可用");
            var sla=db.required("SELECT * FROM sla_rule WHERE priority=? AND enabled=1",body.priority());Instant now=clock.instant();
            if((body.appointmentStartAt()==null)!=(body.appointmentEndAt()==null))throw ApiException.bad("预约开始与结束时间须同时填写");
            if(body.appointmentStartAt()!=null&&(body.appointmentStartAt().isBefore(now)||!body.appointmentEndAt().isAfter(body.appointmentStartAt())))throw ApiException.bad("预约时间无效");
            long id=db.insert("repair_order",Db.fields("order_no","CF"+UUID.randomUUID().toString().replace("-","").substring(0,24).toUpperCase(Locale.ROOT),"facility_scope",scope,"location_text",body.locationText(),"billing_version",1,"resident_id",user.id(),"community_id",room.get("communityId"),"room_id",body.roomId(),"category_id",body.categoryId(),"title",body.title(),"description",body.description(),"priority",body.priority(),"status","SUBMITTED","status_changed_at",now,"response_started_at",now,"response_deadline_at",now.plusSeconds(Db.integer(sla,"responseMinutes")*60L),"sla_rule_id",sla.get("id"),"response_minutes_snapshot",sla.get("responseMinutes"),"completion_minutes_snapshot",sla.get("completionMinutes"),"appointment_start_at",body.appointmentStartAt(),"appointment_end_at",body.appointmentEndAt()));
            if(body.analysisId()!=null)db.execute("UPDATE ai_analysis_record SET order_id=?,adopted_result=JSON_OBJECT('categoryId',?,'priority',?,'title',?,'description',?) WHERE id=? AND user_id=? AND order_id IS NULL AND status='SUCCESS'",id,body.categoryId().toString(),body.priority(),AiPrivacy.redact(body.title(),user.nickname()),AiPrivacy.redact(body.description(),user.nickname()),body.analysisId(),user.id());
            attach(id,0,"FAULT",body.attachmentIds());var order=db.required("SELECT * FROM repair_order WHERE id=?",id);log(order,null,"create",null);notifications.order(order,"create",null);
            return read(id);
        });
    }
    @Transactional public Object action(long id,String action,OrderController.Action body,String key){
        return idempotency.run("order:"+id+":"+action,key,body,()->{
            var order=db.required("SELECT * FROM repair_order WHERE id=? FOR UPDATE",id);checkRead(order);
            var user=CurrentUser.get();String actor=OrderRules.actor(action);user.role(actor);
            if(actor.equals("ADMIN")){user.community(Db.id(order,"communityId"));user.permission("repair:assign");}
            if(actor.equals("RESIDENT")&&Db.id(order,"residentId")!=user.id())throw ApiException.forbidden();
            if(actor.equals("WORKER")){user.permission("repair:update");if(user.workerId()==null||!Objects.equals(order.get("workerId"),user.workerId()))throw ApiException.forbidden();}
            if(Db.integer(order,"version")!=body.version())throw ApiException.conflict();
            if(action.equals("review")&&body.approved()==null)throw ApiException.bad("请明确审核结果");
            String old=(String)order.get("status"),next=OrderRules.next(old,action,Boolean.TRUE.equals(body.approved()));
            if(Set.of("reject","cancel","revoke","rework").contains(action)||(action.equals("review")&&!Boolean.TRUE.equals(body.approved())))requireText(body.reason(),500,"请填写原因");
            if(Set.of("assign","start","finish","confirm").contains(action))billing.requireAccepted(order);
            Long previousUser=null;
            if(order.get("workerId")!=null){var worker=db.required("SELECT * FROM worker_profile WHERE id=? FOR UPDATE",order.get("workerId"));previousUser=Db.id(worker,"userId");}
            Map<String,Object> changes=Db.fields("status",next,"status_changed_at",clock.instant(),"updated_at",clock.instant(),"version",body.version()+1);
            switch(action){
                case "review" -> {changes.put("reviewer_id",user.id());changes.put("reviewed_at",clock.instant());changes.put("review_reason",body.reason());}
                case "cancel" -> {changes.put("cancel_reason",body.reason());changes.put("canceled_at",clock.instant());}
                case "assign" -> assign(order,body,changes);
                case "accept" -> {changes.put("accepted_at",clock.instant());changes.put("completion_deadline_at",clock.instant().plusSeconds(Db.integer(order,"completionMinutesSnapshot")*60L));db.execute("UPDATE repair_assignment SET status='ACCEPTED',accepted_at=? WHERE id=?",clock.instant(),order.get("currentAssignmentId"));}
                case "reject","revoke" -> {db.execute("UPDATE repair_assignment SET status=?,ended_at=?,reason=? WHERE id=?",action.equals("reject")?"REJECTED":"REVOKED",clock.instant(),body.reason(),order.get("currentAssignmentId"));clearWorker(changes);}
                case "start" -> changes.put("started_at",clock.instant());
                case "finish" -> {requireText(body.content(),2000,"请填写维修结果");db.insert("repair_work_record",Db.fields("order_id",id,"worker_id",order.get("workerId"),"round_no",order.get("roundNo"),"assignment_id",order.get("currentAssignmentId"),"record_type","RESULT","content",body.content(),"finished_at",clock.instant()));attach(id,Db.integer(order,"roundNo"),"RESULT",body.attachmentIds());changes.put("finished_at",clock.instant());db.execute("UPDATE repair_assignment SET status='FINISHED',ended_at=? WHERE id=?",clock.instant(),order.get("currentAssignmentId"));}
                case "confirm" -> {changes.put("closed_at",clock.instant());billing.onConfirmed(order);}
                case "rework" -> {clearWorker(changes);changes.put("round_no",Db.integer(order,"roundNo")+1);changes.put("response_started_at",clock.instant());changes.put("response_deadline_at",clock.instant().plusSeconds(Db.integer(order,"responseMinutesSnapshot")*60L));changes.put("finished_at",null);}
                default -> {}
            }
            List<Object> args=new ArrayList<>(changes.values());args.add(id);args.add(body.version());
            if(db.execute("UPDATE repair_order SET "+String.join(",",changes.keySet().stream().map(k->k+"=?").toList())+" WHERE id=? AND version=?",args.toArray())!=1)throw ApiException.conflict();
            var updated=db.required("SELECT * FROM repair_order WHERE id=?",id);log(updated,order,action,body.reason());notifications.order(updated,action,previousUser);return read(id);
        });
    }
    private void clearWorker(Map<String,Object> changes){for(String field:List.of("worker_id","current_assignment_id","accepted_at","started_at","completion_deadline_at"))changes.put(field,null);}
    private void assign(Map<String,Object> order,OrderController.Action body,Map<String,Object> changes){
        if(body.workerId()==null)throw ApiException.bad("请选择维修人员");
        var worker=db.required("SELECT w.*,s.status AS user_status FROM worker_profile w JOIN sys_user s ON s.id=w.user_id WHERE w.id=? FOR UPDATE",body.workerId());
        if(!"AVAILABLE".equals(worker.get("workStatus"))||!"ENABLED".equals(worker.get("userStatus")))throw ApiException.bad("维修人员当前不可用");
        if(db.count("SELECT COUNT(*) FROM worker_skill WHERE worker_id=? AND category_id=?",body.workerId(),order.get("categoryId"))==0||db.count("SELECT COUNT(*) FROM worker_service_community WHERE worker_id=? AND community_id=?",body.workerId(),order.get("communityId"))==0)throw ApiException.bad("维修人员技能或服务区域不匹配");
        if(db.count("SELECT COUNT(*) FROM repair_order WHERE worker_id=? AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')",body.workerId())>=Db.integer(worker,"maxActiveOrders"))throw new ApiException(409,"WORKER_CAPACITY_CONFLICT","维修人员任务已满，请选择其他人员");
        String type=Objects.toString(body.dispatchType(),"MANUAL");if(!Set.of("MANUAL","SMART").contains(type))throw ApiException.bad("派单方式无效");
        Object score=null;
        if(type.equals("SMART")){if(body.recommendationId()==null)throw ApiException.bad("请先获取推荐结果");var rec=db.required("SELECT * FROM dispatch_recommendation WHERE id=?",body.recommendationId());if(Db.id(rec,"orderId")!=Db.id(order,"id")||Db.id(rec,"workerId")!=body.workerId()||Db.integer(rec,"orderVersion")!=Db.integer(order,"version"))throw ApiException.conflict();score=rec.get("score");}
        long assignment=db.insert("repair_assignment",Db.fields("order_id",order.get("id"),"worker_id",body.workerId(),"round_no",order.get("roundNo"),"assigned_by",CurrentUser.get().id(),"dispatch_type",type,"score",score,"status","PENDING","assigned_at",clock.instant(),"recommendation_id",type.equals("SMART")?body.recommendationId():null));
        changes.put("worker_id",body.workerId());changes.put("current_assignment_id",assignment);
    }
    private void attach(long orderId,int round,String type,List<Long> ids){
        if(ids==null)return;if(ids.size()>9||new HashSet<>(ids).size()!=ids.size())throw ApiException.bad("每次最多9张图片且不能重复");
        for(long id:ids){var asset=db.required("SELECT * FROM file_asset WHERE id=? FOR UPDATE",id);if(Db.id(asset,"uploaderId")!=CurrentUser.get().id()||!"TEMP".equals(asset.get("status")))throw ApiException.forbidden();if(asset.get("expiresAt") instanceof Instant expiry&&expiry.isBefore(clock.instant()))throw ApiException.bad("图片已过期，请重新上传");db.insert("repair_order_media",Db.fields("order_id",orderId,"file_id",id,"type",type,"round_no",round));db.execute("UPDATE file_asset SET status='ATTACHED',expires_at=NULL WHERE id=?",id);}
    }
    private void log(Map<String,Object> order,Map<String,Object> old,String action,String reason){db.insert("repair_order_log",Db.fields("order_id",order.get("id"),"old_status",old==null?null:old.get("status"),"new_status",order.get("status"),"operator_id",CurrentUser.get().id(),"remark",reason,"action",action,"round_no",order.get("roundNo"),"old_version",old==null?null:old.get("version"),"new_version",order.get("version"),"request_id",MDC.get("requestId")));}
    public Object logs(long id){read(id);return db.rows("SELECT * FROM repair_order_log WHERE order_id=? ORDER BY new_version",id);}
    public Object records(long id){read(id);return db.rows("SELECT * FROM repair_work_record WHERE order_id=? ORDER BY id",id);}
    public Object media(long id){read(id);return db.rows("SELECT id,file_id,type,round_no FROM repair_order_media WHERE order_id=? ORDER BY id",id);}
    @Transactional public Object progress(long id,String content){var order=db.required("SELECT * FROM repair_order WHERE id=? FOR UPDATE",id);var user=CurrentUser.get();user.role("WORKER");if(!Objects.equals(order.get("workerId"),user.workerId())||!"IN_PROGRESS".equals(order.get("status")))throw ApiException.forbidden();billing.requireAccepted(order);requireText(content,2000,"请填写维修记录");return db.insert("repair_work_record",Db.fields("order_id",id,"worker_id",user.workerId(),"round_no",order.get("roundNo"),"assignment_id",order.get("currentAssignmentId"),"record_type","PROGRESS","content",content));}
    public static void requireText(String text,int length,String message){if(text==null||text.isBlank()||text.length()>length)throw ApiException.bad(message);}
}
