package com.communityfix.module.repair;

import com.communityfix.common.ApiException;
import java.util.Set;

public final class OrderRules {
    private OrderRules(){}
    public static String next(String status,String action,boolean approved) {
        return switch(action) {
            case "review" -> from(status,Set.of("SUBMITTED"),approved?"PENDING_DISPATCH":"REJECTED");
            case "cancel" -> from(status,Set.of("SUBMITTED","PENDING_DISPATCH"),"CANCELED");
            case "assign" -> from(status,Set.of("PENDING_DISPATCH"),"ASSIGNED");
            case "accept" -> from(status,Set.of("ASSIGNED"),"ACCEPTED");
            case "reject" -> from(status,Set.of("ASSIGNED"),"PENDING_DISPATCH");
            case "revoke" -> from(status,Set.of("ASSIGNED","ACCEPTED"),"PENDING_DISPATCH");
            case "start" -> from(status,Set.of("ACCEPTED"),"IN_PROGRESS");
            case "finish" -> from(status,Set.of("IN_PROGRESS"),"PENDING_CONFIRM");
            case "confirm" -> from(status,Set.of("PENDING_CONFIRM"),"CLOSED");
            case "rework" -> from(status,Set.of("PENDING_CONFIRM"),"PENDING_DISPATCH");
            default -> throw ApiException.bad("未知工单操作");
        };
    }
    private static String from(String status,Set<String> allowed,String next){if(!allowed.contains(status))throw ApiException.conflict();return next;}
    public static String actor(String action){return switch(action){case "review","assign","revoke"->"ADMIN";case "cancel","confirm","rework"->"RESIDENT";case "accept","reject","start","finish"->"WORKER";default->throw ApiException.bad("未知工单操作");};}
}
