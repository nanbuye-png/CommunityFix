package com.communityfix.module.notification;

import com.communityfix.common.Db;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class NotificationService {
    private final Db db;
    private final WechatSubscriptionService subscriptions;
    public NotificationService(Db db,WechatSubscriptionService subscriptions){this.db=db;this.subscriptions=subscriptions;}
    public void send(long user,Long order,String key,String title,String content){db.execute("INSERT INTO notification(user_id,order_id,event_key,title,content) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",user,order,key,title,content);}
    public void order(Map<String,Object> order,String action,Long previousWorkerUser){
        long id=Db.id(order,"id");String key="order:"+id+":"+order.get("version")+":"+action;
        Set<Long> recipients=new HashSet<>();recipients.add(Db.id(order,"residentId"));
        db.rows("SELECT user_id FROM admin_community WHERE community_id=?",order.get("communityId")).forEach(r->recipients.add(Db.id(r,"userId")));
        if(order.get("workerId")!=null){var worker=db.required("SELECT user_id FROM worker_profile WHERE id=?",order.get("workerId"));recipients.add(Db.id(worker,"userId"));}
        if(previousWorkerUser!=null)recipients.add(previousWorkerUser);
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
            send(recipient,id,key,"报修进度已更新",order.get("orderNo")+" · "+status);
        }
        // 当前只配置“接单成功通知”模板；微信订阅消息仅发给提交报修的居民。
        if("accept".equals(action))subscriptions.enqueueOrder(Db.id(order,"residentId"),id,key,(String)order.get("orderNo"),status);
    }
}
