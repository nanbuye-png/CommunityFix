package com.communityfix.module.notification;

import com.communityfix.common.Db;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class NotificationService {
    private final Db db;
    private final WechatSubscriptionService subscriptions;
    public NotificationService(Db db,WechatSubscriptionService subscriptions){this.db=db;this.subscriptions=subscriptions;}
    public void quote(Map<String,Object> order,long quote,long amountFen){
        send(Db.id(order,"residentId"),Db.id(order,"id"),"quote:"+quote,"维修报价待确认","请查看完整费用明细，并确认或拒绝本版报价。");
        subscriptions.enqueueConfirmation(order,quote,amountFen);
    }
    public void send(long user,Long order,String key,String title,String content){db.execute("INSERT INTO notification(user_id,order_id,event_key,title,content) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",user,order,key,title,content);}
    public void order(Map<String,Object> order,String action,Long previousWorkerUser){
        long id=Db.id(order,"id");String key="order:"+id+":"+order.get("version")+":"+action;
        Set<Long> recipients=new HashSet<>();
        boolean residentMilestone="accept".equals(action)||"finish".equals(action);
        db.rows("SELECT user_id FROM admin_community WHERE community_id=?",order.get("communityId")).forEach(r->recipients.add(Db.id(r,"userId")));
        if(order.get("workerId")!=null){var worker=db.required("SELECT user_id FROM worker_profile WHERE id=?",order.get("workerId"));recipients.add(Db.id(worker,"userId"));}
        if(previousWorkerUser!=null)recipients.add(previousWorkerUser);
        if(residentMilestone)recipients.add(Db.id(order,"residentId"));
        else recipients.remove(Db.id(order,"residentId"));
        String status = switch ((String)order.get("status")) {
            case "SUBMITTED" -> "待审核";
            case "REJECTED" -> "已驳回";
            case "PENDING_DISPATCH" -> "待派单";
            case "ASSIGNED" -> "待接单";
            case "ACCEPTED" -> "已接单";
            case "IN_PROGRESS" -> "维修中";
            case "PENDING_CONFIRM" -> "待验收";
            case "CLOSED" -> "已完成";
            case "CANCELED" -> "已取消";
            default -> "状态更新";
        };
        for(long recipient:recipients){
            send(recipient,id,key,"finish".equals(action)?"维修已完成，请验收":"报修进度已更新",order.get("orderNo")+" · "+status+("finish".equals(action)?"，请查看维修结果并验收或申请返修":""));
        }
        // 两个业务节点独立消费各自模板的授权，仅通知报修居民。
        if("accept".equals(action))subscriptions.enqueueAcceptance(order,key);
        if("finish".equals(action))subscriptions.enqueueCompletion(order,key);
        if("confirm".equals(action)){
            var bill=db.row("SELECT id,amount_fen FROM repair_bill WHERE order_id=? AND status='UNPAID'",id);
            if(bill!=null&&Db.id(bill,"amountFen")>0){
                long billId=Db.id(bill,"id"),amount=Db.id(bill,"amountFen");
                send(Db.id(order,"residentId"),id,"bill:"+billId,"维修账单待支付","订单已验收，请进入费用明细查看并支付账单。当前仅支持模拟支付，不会真实扣款。");
                subscriptions.enqueuePaymentDue(order,billId,amount);
            }
        }
    }
}
