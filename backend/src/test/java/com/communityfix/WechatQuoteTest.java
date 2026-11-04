package com.communityfix;

import com.communityfix.module.notification.WechatMessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class WechatQuoteTest {
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
