package com.communityfix.module.ai;

import com.communityfix.common.Db;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiDailyUsage {
 private final Db db;private final long threshold;
 public AiDailyUsage(Db db,@Value("${communityfix.ai.daily-token-warning:100000}") long threshold){if(threshold<1)throw new IllegalArgumentException("AI daily warning must be positive");this.db=db;this.threshold=threshold;}
 @Transactional public void record(ModelRoutes.Scene scene,ChatCompletionsClient.Usage usage){
  Long tokens=usage==null?null:usage.totalTokens();
  db.execute("INSERT INTO ai_daily_usage(usage_date,scene,requests,reported_tokens,unknown_usage) VALUES (DATE(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)),?,1,?,?) ON DUPLICATE KEY UPDATE requests=requests+1,reported_tokens=reported_tokens+VALUES(reported_tokens),unknown_usage=unknown_usage+VALUES(unknown_usage)",scene.name(),tokens==null?0:tokens,tokens==null?1:0);
  if(db.execute("UPDATE ai_daily_usage SET alerted=TRUE WHERE usage_date=DATE(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)) AND scene=? AND reported_tokens>=? AND alerted=FALSE",scene.name(),threshold)>0)
   org.slf4j.LoggerFactory.getLogger(AiDailyUsage.class).warn("AI_DAILY_TOKEN_WARNING scene={} threshold={} timezone=Asia/Shanghai",scene,threshold);
 }
}
