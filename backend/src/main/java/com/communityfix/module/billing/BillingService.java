package com.communityfix.module.billing;

import com.communityfix.common.*;
import com.communityfix.module.repair.OrderService;
import com.communityfix.module.notification.NotificationService;
import com.communityfix.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.*;

@Service
public class BillingService {
    @org.springframework.beans.factory.annotation.Autowired private AutomaticQuoteService automaticQuotes;
    @org.springframework.beans.factory.annotation.Autowired private ReferencePriceService referencePrices;
    @org.springframework.beans.factory.annotation.Autowired private com.communityfix.module.notification.WechatSubscriptionService subscriptions;
    private final Db db;private final OrderService orders;private final BillingPolicy policy;private final IdempotencyService idem;private final NotificationService notifications;private final ObjectMapper json;private final boolean mockEnabled;
    public BillingService(Db db,OrderService orders,BillingPolicy policy,IdempotencyService idem,NotificationService notifications,ObjectMapper json,
        @Value("${communityfix.billing.mock-enabled:false}") boolean mockEnabled,@Value("${communityfix.demo-enabled:false}") boolean demo){
        if(mockEnabled&&!demo)throw new IllegalArgumentException("Mock payment requires demo mode");
        this.db=db;this.orders=orders;this.policy=policy;this.idem=idem;this.notifications=notifications;this.json=json;this.mockEnabled=mockEnabled;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Object view(long id) {
        var order=orders.read(id);
        var bill = db.row("SELECT * FROM repair_bill WHERE order_id=?", id);
        var serverTime = databaseTime();
        var attempt = bill == null ? null : db.row("""
            SELECT attempt_no,amount_fen,created_at,expires_at,status
            FROM repair_payment_attempt WHERE bill_id=? ORDER BY id DESC LIMIT 1
            """, bill.get("id"));
        if (attempt != null && isExpired(attempt,serverTime)) attempt.put("status","EXPIRED");
        return Db.fields("quotes",db.rows("SELECT * FROM repair_quote WHERE order_id=? ORDER BY revision DESC",id),
            "acceptedQuote",policy.accepted(id),"bill",bill,"paymentAttempt",attempt,"serverTime",serverTime,
            "payments",db.rows("SELECT p.* FROM repair_payment p JOIN repair_bill b ON b.id=p.bill_id WHERE b.order_id=?",id),
            "mockEnabled",mockEnabled,"referencePrice",automaticQuotes.reference(order));
    }
    private Instant databaseTime() {
        return (Instant) db.required("SELECT UTC_TIMESTAMP(3) AS server_time").get("serverTime");
    }

    static boolean isExpired(Map<String,Object> attempt, Instant databaseTime) {
        return "EXPIRED".equals(attempt.get("status")) ||
            ("PENDING".equals(attempt.get("status")) && !databaseTime.isBefore((Instant)attempt.get("expiresAt")));
    }
    private Map<String,Object> lock(long id){var o=db.required("SELECT * FROM repair_order WHERE id=? FOR UPDATE",id);orders.checkRead(o);if(Db.integer(o,"billingVersion")!=1)throw ApiException.bad("历史工单不启用收费");return o;}
    @Transactional public Object referencePrice(long id,BillingController.ReferencePrice body,String key){return idem.run("billing:reference-price:"+id,key,body,()->{
        var o=lock(id);manager(o);
        referencePrices.save(Db.id(o,"communityId"),Db.id(o,"categoryId"),body);
        automaticQuotes.create(o);audit("REFERENCE_PRICE_UPDATE",id);return view(id);
    });}
    private void manager(Map<String,Object> o){CurrentUser.get().community(Db.id(o,"communityId"));CurrentUser.get().permission("repair:assign");}
    private void owner(Map<String,Object> o){CurrentUser.get().role("RESIDENT");if(Db.id(o,"residentId")!=CurrentUser.get().id())throw ApiException.forbidden();}
    private void quoteAuthor(Map<String,Object> o){
        var user=CurrentUser.get();
        if(user.hasRole("WORKER")&&user.workerId()!=null&&Objects.equals(o.get("workerId"),user.workerId())){user.permission("repair:update");return;}
        manager(o);
    }
    private void audit(String action,long id){db.insert("sys_operation_log",Db.fields("operator_id",CurrentUser.get().id(),"action",action,"target_type","repair_billing","target_id",id,"result","SUCCESS","request_id",Optional.ofNullable(MDC.get("requestId")).orElseGet(()->UUID.randomUUID().toString())));}
    @Transactional public Object scope(long id,BillingController.Scope body,String key){return idem.run("billing:scope:"+id,key,body,()->{
        var o=lock(id);manager(o);if(!Set.of("SUBMITTED","PENDING_DISPATCH").contains(o.get("status"))||o.get("currentAssignmentId")!=null||db.count("SELECT COUNT(*) FROM repair_assignment WHERE order_id=?",id)>0)throw ApiException.conflict();
        if(body.version()==null&&!"SUBMITTED".equals(o.get("status"))||body.version()!=null&&body.version()!=Db.integer(o,"version"))throw ApiException.conflict();
        boolean changed=!body.facilityScope().equals(o.get("facilityScope"));
        if(changed)db.execute("UPDATE repair_quote SET status='SUPERSEDED' WHERE order_id=? AND status IN ('PROPOSED','ACCEPTED')",id);
        db.execute("UPDATE repair_order SET facility_scope=?,location_text=?,version=version+1 WHERE id=?",body.facilityScope(),body.locationText(),id);
        db.insert("repair_order_log",Db.fields("order_id",id,"old_status",o.get("status"),"new_status",o.get("status"),"operator_id",CurrentUser.get().id(),"remark",body.reason(),"action","billing_scope","round_no",o.get("roundNo"),"old_version",o.get("version"),"new_version",Db.integer(o,"version")+1,"request_id",MDC.get("requestId")));
        if(changed)automaticQuotes.create(db.required("SELECT * FROM repair_order WHERE id=?",id));audit("BILLING_SCOPE",id);return view(id);
    });}
    @Transactional public Object quote(long id,BillingController.Quote body,String key){return idem.run("billing:quote:"+id,key,body,()->{
        var o=lock(id);quoteAuthor(o);if(!policy.chargeable(o)||!Set.of("PENDING_DISPATCH","ASSIGNED","ACCEPTED","IN_PROGRESS").contains(o.get("status")))throw ApiException.conflict();
        var latest=db.row("SELECT revision FROM repair_quote WHERE order_id=? ORDER BY revision DESC LIMIT 1",id);int revision=latest==null?0:Db.integer(latest,"revision");if(revision!=body.revision())throw ApiException.conflict();
        long total=0;for(var item:body.items())total=Math.addExact(total,Math.multiplyExact(item.quantity(),item.unitPriceFen()));
        if(total<=0||total>10000000)throw ApiException.bad("单张报价总额须为0.01至100000元");
        String items;try{items=json.writeValueAsString(body.items());}catch(Exception e){throw new IllegalStateException(e);}
        db.execute("UPDATE repair_quote SET status='SUPERSEDED' WHERE order_id=? AND status='PROPOSED'",id);
        long q=db.insert("repair_quote",Db.fields("order_id",id,"revision",revision+1,"items_json",items,"amount_fen",total,"reason",body.reason(),"created_by",CurrentUser.get().id()));
        notifications.quote(o,q,total);audit("QUOTE_CREATE",q);return view(id);
    });}
    @Transactional public Object decision(long id,BillingController.Decision body,String key){return idem.run("billing:decision:"+id,key,body,()->{
        var o=lock(id);owner(o);if(!Set.of("PENDING_DISPATCH","ASSIGNED","ACCEPTED","IN_PROGRESS").contains(o.get("status")))throw ApiException.conflict();
        var q=db.required("SELECT * FROM repair_quote WHERE id=? AND order_id=? FOR UPDATE",body.quoteId(),id);
        if(!"PROPOSED".equals(q.get("status")))throw ApiException.conflict();
        db.execute("UPDATE repair_quote SET status=?,decided_by=?,decided_at=UTC_TIMESTAMP(3) WHERE id=?",body.accepted()?"ACCEPTED":"REJECTED",CurrentUser.get().id(),body.quoteId());
        for(var admin:db.rows("SELECT user_id FROM admin_community WHERE community_id=?",o.get("communityId")))notifications.send(Db.id(admin,"userId"),id,"quote-decision:"+body.quoteId(),"维修报价已回复",body.accepted()?"居民已确认报价。":"居民已拒绝本次报价，请协调处理；此前已确认报价仍有效。");
        audit(body.accepted()?"QUOTE_ACCEPT":"QUOTE_REJECT",body.quoteId());return view(id);
    });}
    private Map<String,Object> lockPayableBill(long id,long billId) {
        if(!mockEnabled)throw new ApiException(409,"MOCK_PAYMENT_DISABLED","模拟支付未开启；当前不支持真实扣款");
        var o=lock(id);owner(o);if(!"CLOSED".equals(o.get("status")))throw ApiException.conflict();
        return db.required("SELECT * FROM repair_bill WHERE id=? AND order_id=? AND payer_id=? FOR UPDATE",billId,id,CurrentUser.get().id());
    }

    @Transactional
    public Object startPayment(long id, BillingController.StartPayment body, String key) {
        var bill = lockPayableBill(id, body.billId());
        return idem.run("billing:start-payment:"+id,key,body,()->{
            if (!"UNPAID".equals(bill.get("status"))) throw ApiException.conflict();
            db.execute("""
                UPDATE repair_payment_attempt SET status='EXPIRED'
                WHERE bill_id=? AND status='PENDING' AND expires_at<=UTC_TIMESTAMP(3)
                """,body.billId());
            if (db.row("SELECT id FROM repair_payment_attempt WHERE pending_bill_id=? FOR UPDATE",body.billId()) == null) {
                db.execute("""
                    INSERT INTO repair_payment_attempt (bill_id,attempt_no,amount_fen,created_at,expires_at)
                    VALUES (?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 30 MINUTE)
                    """,body.billId(),"MOCK-"+UUID.randomUUID(),bill.get("amountFen"));
            }
            return view(id);
        });
    }

    @Transactional public Object pay(long id,BillingController.Pay body,String key){
        var bill=lockPayableBill(id,body.billId());
        if(body.attemptNo()==null || body.attemptNo().isBlank())
            throw new ApiException(409,"PAYMENT_ATTEMPT_REQUIRED","请刷新账单后重新发起支付");
        return idem.run("billing:mock-pay:"+id,key,body,()->{
        var attempt=db.required("SELECT status,expires_at FROM repair_payment_attempt WHERE bill_id=? AND attempt_no=? FOR UPDATE",body.billId(),body.attemptNo());
        if("SUCCEEDED".equals(attempt.get("status")) && "MOCK_PAID".equals(bill.get("status")))return view(id);
        if(isExpired(attempt,databaseTime()))
            throw new ApiException(409,"PAYMENT_ATTEMPT_EXPIRED","本次支付已超时，请重新支付");
        if(!"UNPAID".equals(bill.get("status")))throw ApiException.conflict();
        db.execute("UPDATE repair_payment_attempt SET status='SUCCEEDED' WHERE attempt_no=?",body.attemptNo());
        db.insert("repair_payment",Db.fields("bill_id",body.billId(),"payment_no",body.attemptNo(),"channel","MOCK","amount_fen",bill.get("amountFen"),"status","MOCK_SUCCESS"));
        db.execute("UPDATE repair_bill SET status='MOCK_PAID',paid_at=UTC_TIMESTAMP(3) WHERE id=?",body.billId());
        for(var admin:db.rows("SELECT a.user_id FROM admin_community a JOIN repair_order o ON a.community_id=o.community_id WHERE o.id=?",id))notifications.send(Db.id(admin,"userId"),id,"mock-paid:"+body.billId(),"账单模拟支付完成","本次为模拟交易，不代表真实到账。");
        audit("PAYMENT_MOCK",body.billId());return view(id);
    });}
}
