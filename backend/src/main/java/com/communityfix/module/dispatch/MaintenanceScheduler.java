package com.communityfix.module.dispatch;

import com.communityfix.common.Db;
import com.communityfix.module.repair.FileService;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceScheduler {
    private static final Logger log=LoggerFactory.getLogger(MaintenanceScheduler.class);
    private final Db db;private final SlaService sla;private final FileService files;private final int retentionDays;
    public MaintenanceScheduler(Db db,SlaService sla,FileService files,@Value("${communityfix.ai.retention-days:30}")int retentionDays){if(retentionDays<1||retentionDays>3650)throw new IllegalArgumentException("AI retention days must be between 1 and 3650");this.db=db;this.sla=sla;this.files=files;this.retentionDays=retentionDays;}
    @Scheduled(fixedDelayString="${communityfix.maintenance.scan-delay-ms:60000}",initialDelayString="${communityfix.maintenance.scan-delay-ms:60000}") public void scan(){
        long cursor=0;while(true){var batch=db.rows("SELECT id FROM repair_order WHERE id>? AND ((status IN ('SUBMITTED','PENDING_DISPATCH','ASSIGNED') AND response_deadline_at<=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL response_minutes_snapshot*12 SECOND)) OR (status IN ('ACCEPTED','IN_PROGRESS') AND completion_deadline_at<=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL completion_minutes_snapshot*12 SECOND))) ORDER BY id LIMIT 200",cursor);if(batch.isEmpty())break;for(var row:batch){cursor=Db.id(row,"id");try{sla.inspect(cursor);}catch(Exception ex){log.warn("SLA inspection failed for order {}",cursor,ex);}}}
    }
    @Scheduled(fixedDelayString="${communityfix.maintenance.cleanup-delay-ms:3600000}",initialDelayString="${communityfix.maintenance.cleanup-delay-ms:3600000}") public void cleanup(){
        db.execute("DELETE FROM idempotency_record WHERE expires_at<UTC_TIMESTAMP(3)");db.execute("DELETE FROM ai_analysis_record WHERE created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL ? DAY)",retentionDays);db.execute("DELETE FROM ai_request_permit WHERE requested_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY)");
        var assets=db.rows("SELECT id,object_key FROM file_asset WHERE (status='TEMP' AND expires_at<UTC_TIMESTAMP(3)) OR status='PENDING_DELETE' ORDER BY id LIMIT 100");
        for(var asset:assets){if(db.execute("UPDATE file_asset SET status='PENDING_DELETE' WHERE id=? AND ((status='TEMP' AND expires_at<UTC_TIMESTAMP(3)) OR status='PENDING_DELETE') AND NOT EXISTS (SELECT 1 FROM repair_order_media WHERE file_id=?)",asset.get("id"),asset.get("id"))==0)continue;try{files.remove((String)asset.get("objectKey"));db.execute("UPDATE file_asset SET status='DELETED' WHERE id=?",asset.get("id"));}catch(Exception ex){log.warn("Temporary file cleanup pending for asset {}",asset.get("id"));}}
    }
}
