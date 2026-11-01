package com.communityfix;

import com.communityfix.common.ApiException;
import com.communityfix.module.repair.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class OrderRulesTest {
    @Test void allLegalAndIllegalEdges(){
        Map<String,Map<String,String>> matrix=Map.ofEntries(
          Map.entry("review",Map.of("SUBMITTED","PENDING_DISPATCH")),Map.entry("cancel",Map.of("SUBMITTED","CANCELED","PENDING_DISPATCH","CANCELED")),
          Map.entry("assign",Map.of("PENDING_DISPATCH","ASSIGNED")),Map.entry("accept",Map.of("ASSIGNED","ACCEPTED")),Map.entry("reject",Map.of("ASSIGNED","PENDING_DISPATCH")),
          Map.entry("revoke",Map.of("ASSIGNED","PENDING_DISPATCH","ACCEPTED","PENDING_DISPATCH")),Map.entry("start",Map.of("ACCEPTED","IN_PROGRESS")),
          Map.entry("finish",Map.of("IN_PROGRESS","PENDING_CONFIRM")),Map.entry("confirm",Map.of("PENDING_CONFIRM","CLOSED")),Map.entry("rework",Map.of("PENDING_CONFIRM","PENDING_DISPATCH")));
        for(var entry:matrix.entrySet())for(var status:OrderStatus.values()){if(entry.getValue().containsKey(status.name()))assertEquals(entry.getValue().get(status.name()),OrderRules.next(status.name(),entry.getKey(),true));else assertThrows(ApiException.class,()->OrderRules.next(status.name(),entry.getKey(),true));}
        assertEquals("REJECTED",OrderRules.next("SUBMITTED","review",false));
    }
    @Test void unknownActionsFail(){assertThrows(ApiException.class,()->OrderRules.next("CLOSED","force-close",true));}
}
