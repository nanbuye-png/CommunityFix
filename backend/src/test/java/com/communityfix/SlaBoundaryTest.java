package com.communityfix;
import com.communityfix.module.dispatch.SlaService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class SlaBoundaryTest {
    @Test void levelsRespectEightyOneHundredAndOneHundredFiftyPercent(){Instant start=Instant.parse("2026-10-14T00:00:00Z"),deadline=start.plusSeconds(1000);assertEquals(0,SlaService.level(start,deadline,start.plusSeconds(799)));assertEquals(1,SlaService.level(start,deadline,start.plusSeconds(800)));assertEquals(2,SlaService.level(start,deadline,start.plusSeconds(1000)));assertEquals(3,SlaService.level(start,deadline,start.plusSeconds(1500)));}
}
