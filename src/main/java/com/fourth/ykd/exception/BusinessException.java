package com.fourth.ykd.exception;

/* Service 只负责发现并抛出业务错误
HTTP 层负责把错误转换为 HTTP 响应
封装统一  运行时异常(RuntimeException)  类：
EG：天气业务发现城市为空
        ↓
抛出 BusinessException(40001, "城市名称不能为空")
        ↓
全局异常处理器捕获
        ↓
ApiResponse.failure(40001, "城市名称不能为空")
        ↓
返回 JSON 给接口调用者
继承 RuntimeException 后，可以直接从 Service 一直抛到 Controller,最后由全局异常处理器统一捕获*/
public class BusinessException extends RuntimeException{

    private final int code;

    public BusinessException(int code,String message){
        //Java 要求父类构造器调用必须位于构造器第一条有效语句,错误说明交给异常父类保存，后面可以直接exception.getMessage()。
        super(message);
        this.code = code;
    }

    /*为什么 message 不需要 getter？:
    因为继承自 RuntimeException，已经可以调用：exception.getMessage()*/
    public int getCode() {
        return code;
    }

}
