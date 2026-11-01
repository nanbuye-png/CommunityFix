package com.communityfix.module.ai;

public final class AiPrivacy {
    private AiPrivacy() {}
    public static String redact(String text,String nickname) {
        String result=text.replaceAll("1[3-9]\\d{9}","[手机号]").replaceAll("\\d+(栋|单元|室)","[房屋信息]");
        if(nickname!=null&&nickname.length()>1)result=result.replace(nickname,"[居民]");
        if(result.length()<=2000)return result;
        int end=Character.isHighSurrogate(result.charAt(1999))?1999:2000;
        return result.substring(0,end);
    }
}
