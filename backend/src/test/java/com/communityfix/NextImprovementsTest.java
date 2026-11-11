package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.notification.NotificationValidity;
import com.communityfix.module.support.SupportContext;
import com.communityfix.module.ai.ModelRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NextImprovementsTest {
 @Test void exactFaqSkipsHistoryRetrievalAndModel(){
  var db=mock(Db.class);var jobs=mock(com.communityfix.module.support.SupportJobs.class);var knowledge=mock(com.communityfix.module.support.SupportKnowledge.class);var models=mock(com.communityfix.module.ai.ModelGateway.class);
  var user=mock(com.communityfix.security.CurrentUser.class);
  var session=Db.fields("id",1L,"communityId",2L,"userRole","RESIDENT","status","AI","aiGeneration",1);
  var job=Db.fields("generation",1,"messageId",3L);
  when(jobs.session(job)).thenReturn(session);when(jobs.owner(session)).thenReturn(user);
  when(db.required(anyString(),eq(3L))).thenReturn(Db.fields("content","怎么报修"));
  when(knowledge.directAnswer(2L,"RESIDENT","怎么报修")).thenReturn(Optional.of(Db.fields("id",4L,"title","报修","answer","点击提交报修")));
  var ai=new com.communityfix.module.support.SupportAi(db,jobs,knowledge,mock(com.communityfix.module.support.SupportAccess.class),mock(com.communityfix.module.support.SupportService.class),models,new com.fasterxml.jackson.databind.ObjectMapper());
  try{ai.process(job);verify(knowledge,never()).retrieve(any(),anyString(),anyString(),anyList());verifyNoInteractions(models);verify(db,never()).rows(anyString(),any());verify(jobs).finish(eq(job),eq("点击提交报修"),any(),isNull(),anyLong());}finally{ai.stop();}
 }
 @Test void completedReminderRequiresSamePendingAcceptanceVersion() throws Exception {
  Db db=mock(Db.class);var guard=new NotificationValidity(db);
  when(db.required(startsWith("SELECT event_key"),eq(1L))).thenReturn(Db.fields("eventKey","order:42:7:finish","recipientId",3L));
  var order=Db.fields("residentId",3L,"orderNo","CF42","status","PENDING_CONFIRM","version",7);
  when(db.row(startsWith("SELECT resident_id"),eq(42L))).thenReturn(order);
  String payload="{\"kind\":\"COMPLETION\",\"orderNo\":\"CF42\"}";
  assertNull(guard.invalidReason(1,payload));
  order.put("status","CLOSED");assertNotNull(guard.invalidReason(1,payload));
  order.put("status","PENDING_CONFIRM");order.put("version",10);assertNotNull(guard.invalidReason(1,payload));
  order.put("version",7);order.put("residentId",4L);assertNotNull(guard.invalidReason(1,payload));
 }
 @Test void historyBudgetKeepsNewestWholeMessagesWithoutFillingGaps(){
  var latest=Db.fields("content","最新问题");var previous=Db.fields("content","abcdefghij");var old=Db.fields("content","旧");
  assertEquals(List.of(latest),SupportContext.recent(List.of(latest,previous,old),10));
  assertEquals(List.of(latest,previous,old),SupportContext.recent(List.of(latest,previous,old),15));
 }
 @Test void outputBudgetsAreIndependentWithSharedProvider(){
  var env=new MockEnvironment().withProperty("communityfix.ai.endpoint","https://example.com/chat").withProperty("communityfix.ai.api-key","test").withProperty("communityfix.ai.model","test").withProperty("communityfix.ai.support.max-output-tokens","800");
  var routes=new ModelRoutes(env);
  assertEquals(128,routes.forScene(ModelRoutes.Scene.REPAIR).maxTokens());assertEquals(800,routes.forScene(ModelRoutes.Scene.SUPPORT).maxTokens());
  assertEquals(routes.forScene(ModelRoutes.Scene.REPAIR).endpoint(),routes.forScene(ModelRoutes.Scene.SUPPORT).endpoint());
  env.withProperty("communityfix.ai.repair.max-output-tokens","0");assertThrows(IllegalArgumentException.class,()->new ModelRoutes(env));
 }
}
