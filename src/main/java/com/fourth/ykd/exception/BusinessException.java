package com.fourth.ykd.exception;

/* 封装统一异常类：
天气业务发现城市为空
        ↓
抛出 BusinessException(40001, "城市名称不能为空")
        ↓
全局异常处理器捕获
        ↓
ApiResponse.failure(40001, "城市名称不能为空")
        ↓
返回 JSON 给接口调用者*/
public class BusinessException extends RuntimeException{

    private final int code;

    public BusinessException(int code,String message){
        //错误说明交给异常父类保存，后面可以直接exception.getMessage()。
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

}
