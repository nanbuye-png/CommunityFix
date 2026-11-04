package com.communityfix.module.ai;

import java.util.regex.Pattern;

/** Conservative, bounded safeguard for explicit reported incidents, not a diagnosis. */
public final class RepairSafety {
    private RepairSafety() {}
    private static final Pattern INCIDENT = Pattern.compile(
        "明火|冒火|起火|着火|烧着|燃烧|打火|火花|电击感|触电|冒烟|浓烟|被困|无法出来|出不来|烫伤|烫到|正在坠落|正在掉落");
    private static final Pattern NEGATED = Pattern.compile(
        "(?:没有|未见|未发现|未出现|未看到|未闻到|无|并无|不再|并未|不是)[^，。；]{0,8}$");
    private static final Pattern HYPOTHETICAL = Pattern.compile("如果|假如|假设|万一|担心|会不会|是否|能否|例如|比如");
    private static final Pattern CURRENT = Pattern.compile("已经|正在|目前|刚刚|现在|仍在|持续|已出现|已发生");
    private static final Pattern HISTORICAL = Pattern.compile("以前|曾经|去年|上次|之前");

    public static boolean explicitUrgent(String description) {
        for (String clause : description.split("[，。！？；\\n,;!?]+|但是|不过|然而|但|却")) {
            if (HYPOTHETICAL.matcher(clause).find() && !CURRENT.matcher(clause).find()) continue;
            if (HISTORICAL.matcher(clause).find() && !CURRENT.matcher(clause).find()) continue;
            if (clause.matches(".*(?:火|烟).*(?:已|已经)(?:完全)?(?:熄灭|消失|停止).*")) continue;
            var incident = INCIDENT.matcher(clause);
            while (incident.find()) {
                String prefix = clause.substring(0, incident.start());
                // Double negation is not evidence that an incident is absent.
                if (prefix.contains("不是没有") || prefix.contains("并非没有")) return true;
                if (!NEGATED.matcher(prefix).find()) return true;
            }
        }
        return false;
    }

    public static String priority(String modelPriority, boolean urgent) {
        return urgent ? "URGENT" : modelPriority;
    }

    public static String notice(boolean urgent) {
        return urgent ? "描述中包含明确险情，建议按紧急报修并联系人工确认。请勿依据 AI 答复自行进行危险维修。" : null;
    }
}
