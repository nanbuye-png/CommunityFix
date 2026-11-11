package com.communityfix.module.support;

import java.util.*;

/** Character budget, deliberately not presented as an exact tokenizer count. */
public final class SupportContext {
 private SupportContext(){}
 public static List<Map<String,Object>> recent(List<Map<String,Object>> newestFirst,int budget){
  var result=new ArrayList<Map<String,Object>>();int remaining=budget;
  for(var message:newestFirst){int size=Objects.toString(message.get("content"),"").length();if(size>remaining)break;result.add(message);remaining-=size;}
  return result;
 }
}
