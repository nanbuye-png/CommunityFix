package com.communityfix.module.ai;

/** Safe operational codes only; never attach upstream response bodies or exception causes. */
public final class ModelFailure extends RuntimeException {
    private final String code;
    private final Integer httpStatus;
    public ModelFailure(String code){this(code,null);}
    public ModelFailure(String code,Integer httpStatus){super(code);this.code=code;this.httpStatus=httpStatus;}
    public String code(){return code;}
    public Integer httpStatus(){return httpStatus;}
}
