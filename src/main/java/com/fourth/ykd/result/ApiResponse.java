package com.fourth.ykd.result;

public record ApiResponse<T>(int code, String message, T data) {

    //约定0成功非0失败
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}