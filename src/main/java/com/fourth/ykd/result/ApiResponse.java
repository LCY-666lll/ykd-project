package com.fourth.ykd.result;

/*统一返回DTO结果类：HTTP 接口统一返回包装，success / failure。主要给接口测试用
 record 会自动生成：
三个 final 字段
完整构造器
code()
message()
data()
equals()
hashCode()
toString()
* */
public record ApiResponse<T>(int code, String message, T data) {

    /*最终 HTTP JSON 类似：
    {
        "code": 0,
            "message": "success",
            "data": {
        "status": "LOGGED_IN",
                "loggedIn": true
    }
    }
    方法前面的 <T>:这是静态方法自己的泛型声明。
    因为类本身的 T 只有创建对象后才能确定，而静态方法属于类，不属于某个对象，所以静态方法需要自己声明 <T>
    */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /*ApiResponse.failure(40001, "message must not be blank")
    返回 JSON：
    {
        "code": 40001,
            A     "message": "message must not be blank",
            "data": null
    }*/
    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}