package com.communityfix.module.support;
import com.communityfix.module.ai.AiPrivacy;
public final class SupportPrivacy {
 private SupportPrivacy(){}
 public static String redact(String text,String name){
  return AiPrivacy.redact(text,name)
   .replaceAll("(?i)(密码|口令|password|token|api[_-]?key|secret)\\s*[:：=是]?\\s*[^\\s，。；;]+","[凭据已移除]")
   .replaceAll("(?i)Bearer\\s+\\S+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]+","[令牌已移除]")
   .replaceAll("(?:地址|住址|住在)\\s*[:：是]?[^\\n，。；;]+","[地址已移除]")
   .replaceAll("[\\p{IsHan}]{2,}(?:路|街|巷|大道)\\s*\\d+号[^\\s，。；;]*","[地址已移除]");
 }
}
