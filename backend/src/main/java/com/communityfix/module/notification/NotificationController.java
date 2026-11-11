package com.communityfix.module.notification;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/notifications")
public class NotificationController {
    private final Db db;private final WechatSubscriptionService subscriptions;
    public NotificationController(Db db,WechatSubscriptionService subscriptions){this.db=db;this.subscriptions=subscriptions;}
    public record Subscription(@NotBlank String templateId) {}
    @GetMapping("/wechat-subscription") ApiResponse<?> subscription(){return ApiResponse.ok(subscriptions.configuration());}
    @PostMapping("/wechat-subscription") ApiResponse<?> subscribe(@Valid @RequestBody Subscription body){subscriptions.grant(body.templateId());return ApiResponse.ok(null);}
    @GetMapping("/outbox") ApiResponse<?> outbox(@RequestParam(defaultValue="FAILED") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        CurrentUser.get().role("SUPER_ADMIN");
        if(!java.util.Set.of("PENDING","PROCESSING","SENT","FAILED","SKIPPED").contains(status))throw ApiException.bad("通知状态无效");
        return ApiResponse.ok(Pages.result(db.rows("SELECT id,event_key,recipient_id,channel,status,attempts,next_retry_at,last_error,mq_publish_attempts,mq_next_publish_at,mq_last_error,created_at,updated_at FROM notification_outbox WHERE channel='WECHAT' AND status=? ORDER BY id DESC LIMIT ? OFFSET ?",status,Pages.size(size),Pages.offset(page,size)),db.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='WECHAT' AND status=?",status),page,size));
    }
    @GetMapping("/outbox/summary") ApiResponse<?> outboxSummary(){
        CurrentUser.get().role("SUPER_ADMIN");
        var summary=Db.fields("PENDING",0,"PROCESSING",0,"SENT",0,"FAILED",0,"SKIPPED",0);
        for(var row:db.rows("SELECT status,COUNT(*) AS total FROM notification_outbox WHERE channel='WECHAT' GROUP BY status"))if(summary.containsKey(row.get("status")))summary.put((String)row.get("status"),row.get("total"));
        return ApiResponse.ok(summary);
    }
    @PostMapping("/outbox/{id}/retry") @Transactional ApiResponse<?> retry(@PathVariable long id){
        CurrentUser.get().role("SUPER_ADMIN");
        int updated=db.execute("UPDATE notification_outbox SET status='PENDING',attempts=0,next_retry_at=NULL,locked_until=NULL,last_error=NULL,mq_next_publish_at=NULL,mq_last_error=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND channel='WECHAT' AND status='FAILED'",id);
        if(updated==0)throw ApiException.conflict();
        db.insert("sys_operation_log",Db.fields("operator_id",CurrentUser.get().id(),"action","NOTIFICATION_RETRY","target_type","notification_outbox","target_id",id,"result","SUCCESS","request_id",MDC.get("requestId")));
        return ApiResponse.ok(null);
    }
    @GetMapping ApiResponse<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){long user=CurrentUser.get().id();return ApiResponse.ok(Pages.result(db.rows("SELECT * FROM notification WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",user,Pages.size(size),Pages.offset(page,size)),db.count("SELECT COUNT(*) FROM notification WHERE user_id=?",user),page,size));}
    @PostMapping("/{id}/read") ApiResponse<?> read(@PathVariable long id){if(db.row("SELECT id FROM notification WHERE id=? AND user_id=?",id,CurrentUser.get().id())==null)throw ApiException.missing();db.execute("UPDATE notification SET read_status=1,read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE id=? AND user_id=?",id,CurrentUser.get().id());return ApiResponse.ok(null);}
}
