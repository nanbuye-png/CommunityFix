package com.communityfix;

import com.communityfix.security.TokenService;
import com.communityfix.module.dispatch.DispatchService;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityAndDispatchTest {
    private static final String SECRET="0123456789abcdef0123456789abcdef";
    @Test void tokensExpireAndRejectTampering(){var clock=Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"),ZoneOffset.UTC);var service=new TokenService(SECRET,"communityfix","client",clock);var token=service.issue(42,3);assertEquals(42,service.parse(token).userId());assertEquals(3,service.parse(token).version());assertThrows(Exception.class,()->new TokenService(SECRET,"other","client",clock).parse(token));assertThrows(Exception.class,()->new TokenService(SECRET,"communityfix","client",Clock.offset(clock,Duration.ofHours(3))).parse(token));String[] parts=token.split("\\.");assertThrows(Exception.class,()->service.parse(parts[0]+"."+parts[1]+".invalidsignature"));}
    @Test void weightsFavorLowLoadAndColdStartIsStable(){assertEquals(96,DispatchService.score(.9,0,5,1),.0001);assertTrue(DispatchService.score(1,0,5,.6)>DispatchService.score(1,4,5,.6));assertEquals(DispatchService.score(.8,0,5,.6),DispatchService.score(.8,0,5,.6));}
}
