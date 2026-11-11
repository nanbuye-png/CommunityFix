package com.communityfix;

import com.communityfix.module.notification.WechatMessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class WechatQuoteTest {
 @Test void confirmationAndPaymentMapNewTemplatesAndPinSnapshots() throws Exception {
  var s=sender();
  for(boolean payment:new boolean[]{false,true}){
   String template=payment?s.paymentTemplateId():s.confirmationTemplateId();
   String payload=json.writeValueAsString(java.util.Map.of("kind",payment?"PAYMENT_DUE":"CONFIRMATION","templateId",template,"orderNo","CF123","amountFen",12345,"createdAt","2026-11-10 09:00:00","completedAt","2026-11-10 11:00:00","content","灯具维修","page","pages/detail/index?id=1&billing=1"));
   var body=json.readTree(s.messageBody("synthetic",payload));assertEquals(template,body.path("template_id").asText());
   assertEquals(payment?5:3,body.path("data").size());assertEquals("CF123",body.at("/data/character_string1/value").asText());assertEquals("123.45",body.at("/data/amount3/value").asText());
   assertEquals("2026-11-10 09:00:00",body.at(payment?"/data/time22/value":"/data/time6/value").asText());
   if(payment){assertEquals("灯具维修",body.at("/data/thing2/value").asText());assertEquals("2026-11-10 11:00:00",body.at("/data/date10/value").asText());}
   ReflectionTestUtils.setField(s,payment?"paymentTemplate":"confirmationTemplate","changed");assertThrows(IllegalStateException.class,()->s.messageBody("synthetic",payload));
  }
 }
 @Test void completionHasIndependentTemplateAndExactAmount() throws Exception {
  var s=sender();
  for(long amount:new long[]{0,1,12345}){
   var payload=json.writeValueAsString(java.util.Map.of("kind","COMPLETION","templateId",s.completionTemplateId(),"orderNo","CF123","amountFen",amount,"completedAt","2026-11-10 18:30:00","page","pages/detail/index?id=1"));
   var body=json.readTree(s.messageBody("synthetic",payload));assertEquals(s.completionTemplateId(),body.path("template_id").asText());assertEquals(3,body.path("data").size());
   assertEquals(java.math.BigDecimal.valueOf(amount,2).toPlainString(),body.at("/data/amount11/value").asText());assertEquals("CF123",body.at("/data/character_string5/value").asText());assertEquals("2026-11-10 18:30:00",body.at("/data/date9/value").asText());
  }
 }
 @Test void acceptanceUsesAllFiveProvidedFieldsAndPinsTemplate() throws Exception {
  var s=sender();String template=s.acceptanceTemplateId();
  var payload=json.writeValueAsString(java.util.Map.of("kind","ACCEPTANCE","templateId",template,"orderNo","CF123","service","灯具维修","worker","张师傅","acceptedAt","2026-11-10 10:30:00","page","pages/detail/index?id=1"));
  var body=json.readTree(s.messageBody("synthetic",payload));
  assertEquals(5,body.path("data").size());assertEquals("CF123",body.at("/data/character_string7/value").asText());assertEquals("CF123",body.at("/data/character_string30/value").asText());
  assertEquals("灯具维修",body.at("/data/thing27/value").asText());assertEquals("张师傅",body.at("/data/name5/value").asText());assertEquals("2026-11-10 10:30:00",body.at("/data/date4/value").asText());
  ReflectionTestUtils.setField(s,"acceptanceTemplate","changed");assertThrows(IllegalStateException.class,()->s.messageBody("synthetic",payload));
 }
 private final ObjectMapper json=new ObjectMapper();
 private WechatMessageSender sender(){
  var s=new WechatMessageSender(json,"synthetic-app","synthetic-secret","order-template","number1","status2");
  ReflectionTestUtils.setField(s,"quoteTemplate","quote-template");
  ReflectionTestUtils.setField(s,"quoteOrderField","character_string3");
  ReflectionTestUtils.setField(s,"quoteAmountField","amount10");return s;
 }
 @Test void quoteUsesItsOwnTemplateAndExactYuanAmount() throws Exception {
  for(long amount:new long[]{1,12345}){
   var body=json.readTree(sender().messageBody("synthetic-openid","{\"kind\":\"QUOTE\",\"templateId\":\"quote-template\",\"orderNo\":\"CF123\",\"amountFen\":"+amount+",\"page\":\"pages/detail/index?id=1&billing=1\"}"));
   assertEquals("quote-template",body.path("template_id").asText());
   assertEquals("CF123",body.at("/data/character_string3/value").asText());
   assertEquals(amount==1?"0.01":"123.45",body.at("/data/amount10/value").asText());
   assertEquals(2,body.path("data").size());assertTrue(body.path("page").asText().endsWith("&billing=1"));
  }
 }
 @Test void existingAcceptancePayloadStillUsesOrderTemplate() throws Exception {
  var body=json.readTree(sender().messageBody("synthetic","{\"orderNo\":\"CF123\",\"status\":\"accepted\",\"page\":\"detail\"}"));
  assertEquals("order-template",body.path("template_id").asText());assertEquals("accepted",body.at("/data/status2/value").asText());
 }
 @Test void missingOrChangedQuoteConfigurationCannotFallBackToAcceptanceTemplate(){
  var s=sender();assertThrows(IllegalStateException.class,()->s.messageBody("synthetic","{\"kind\":\"QUOTE\",\"templateId\":\"old-template\"}"));
  ReflectionTestUtils.setField(s,"quoteAmountField","");assertFalse(s.quoteEnabled());
  assertThrows(IllegalStateException.class,()->s.messageBody("synthetic","{\"kind\":\"QUOTE\"}"));
 }
}
