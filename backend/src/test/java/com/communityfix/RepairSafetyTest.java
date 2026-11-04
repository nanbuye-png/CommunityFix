package com.communityfix;

import com.communityfix.module.ai.RepairSafety;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepairSafetyTest {
    @Test void explicitIncidentsNeverInheritLowerModelPriority() {
        for (String text : List.of(
            "微波炉运行时内部出现明火，火焰已经蔓延到炉门边缘。",
            "洗衣机内部进水接头断裂，水喷到机器内的带电部分并出现火花，墙外给水管和阀门完好。",
            "电饭煲自带电源线持续打火，桌布已经冒烟。",
            "电梯停在两层之间，轿厢内有人无法出来。",
            "并不是没有火花，插座仍在打火。",
            "未发现明火，但插座正在冒烟。","没有火花但是正在冒烟")) {
            assertTrue(RepairSafety.explicitUrgent(text), text);
            for(String p:List.of("NORMAL","HIGH","URGENT")) assertEquals("URGENT",RepairSafety.priority(p,true));
        }
    }
    @Test void negativeHistoricalAndHypotheticalReportsDoNotTriggerFloor() {
        for(String text:List.of("没有火花或烟雾，其他照明正常。","无人被困，另一部电梯正常。",
            "如果发生明火怎么办？","担心将来会起火。","以前起火，现在已经修好。",
            "火焰已经完全熄灭。","开关不灵，未看到火花，也没有冒烟。"))
            assertFalse(RepairSafety.explicitUrgent(text),text);
        assertEquals("HIGH",RepairSafety.priority("HIGH",false));
        assertEquals("URGENT",RepairSafety.priority("URGENT",false));
    }
}
