package com.communityfix.module.dispatch;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/sla/warnings")
public class SlaController {
    private final Db db;
    public SlaController(Db db) { this.db = db; }

    @GetMapping
    public ApiResponse<?> warnings(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="true") boolean active,
            @RequestParam(required=false) String phase, @RequestParam(required=false) Integer level,
            @RequestParam(required=false) String search) {
        var user = CurrentUser.get();
        user.role("ADMIN");
        user.permission("repair:view");
        int limit = Pages.size(size), offset = Pages.offset(page,size);
        if (phase != null && !phase.isBlank() && !Set.of("RESPONSE","COMPLETION").contains(phase)) throw ApiException.bad("预警阶段无效");
        if (level != null && (level < 1 || level > 3)) throw ApiException.bad("预警等级无效");
        if (search != null && search.length() > 100) throw ApiException.bad("搜索文字过长");
        if (user.communities().isEmpty()) return ApiResponse.ok(Pages.result(List.of(),0,page,size));
        List<Object> args = new ArrayList<>(user.communities());
        String current = "w.round_no=o.round_no AND ((w.phase='RESPONSE' AND o.status IN ('SUBMITTED','PENDING_DISPATCH','ASSIGNED')) "
                + "OR (w.phase='COMPLETION' AND o.status IN ('ACCEPTED','IN_PROGRESS')))";
        String where = " WHERE o.community_id IN (" + String.join(",",Collections.nCopies(args.size(),"?")) + ") "
                + "AND NOT EXISTS (SELECT 1 FROM sla_warning higher WHERE higher.order_id=w.order_id AND higher.round_no=w.round_no "
                + "AND higher.phase=w.phase AND higher.level>w.level)";
        if (active) where += " AND (" + current + ")";
        if (phase != null && !phase.isBlank()) { where += " AND w.phase=?"; args.add(phase); }
        if (level != null) { where += " AND w.level=?"; args.add(level); }
        if (search != null && !search.isBlank()) { where += " AND (o.order_no LIKE ? OR o.title LIKE ?)"; args.add("%"+search.trim()+"%"); args.add("%"+search.trim()+"%"); }
        String from = " FROM sla_warning w JOIN repair_order o ON o.id=w.order_id JOIN community c ON c.id=o.community_id "
                + "LEFT JOIN worker_profile worker ON worker.id=o.worker_id" + where;
        long total = db.count("SELECT COUNT(*)"+from,args.toArray());
        args.add(limit); args.add(offset);
        var rows = db.rows("SELECT w.*,o.order_no,o.title,o.status AS order_status,c.name AS community_name,worker.real_name AS worker_name,"
                + "CASE WHEN " + current + " THEN 1 ELSE 0 END AS currently_active"+from+" ORDER BY w.level DESC,w.deadline_at,w.id DESC LIMIT ? OFFSET ?",args.toArray());
        return ApiResponse.ok(Pages.result(rows,total,page,size));
    }
}
