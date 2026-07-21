package com.fourth.ykd.result;

/*统一返回DTO结果类：HTTP 接口统一返回包装，success / failure。主要给接口测试用*/
public record ApiResponse<T>(int code, String message, T data) {

    /*最终 HTTP JSON 类似：
    {
        "code": 0,
            "message": "success",
            "data": {
        "status": "LOGGED_IN",
                "loggedIn": true
    }
    }*/
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