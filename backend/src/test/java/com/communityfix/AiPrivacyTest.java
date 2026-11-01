package com.communityfix;

import com.communityfix.module.ai.AiPrivacy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiPrivacyTest {
    @Test void removesResidentNamePhoneAndRoomFromRetainedText(){
        String text=AiPrivacy.redact("陈女士在3栋1单元602室报修，联系电话13812345678","陈女士");
        for(String secret:new String[]{"陈女士","13812345678","3栋","1单元","602室"})assertFalse(text.contains(secret));
        assertTrue(text.contains("报修"));
    }
    @Test void expandedMasksStayWithinStorageLimit(){
        assertTrue(AiPrivacy.redact("1室".repeat(1000),"测试").length()<=2000);
        String emoji=AiPrivacy.redact("x"+"1室".repeat(333)+"😀","测试");
        assertFalse(Character.isHighSurrogate(emoji.charAt(emoji.length()-1)));
    }
}
