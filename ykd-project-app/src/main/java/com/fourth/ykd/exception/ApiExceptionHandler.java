package com.fourth.ykd.exception;

import com.fourth.ykd.result.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
/*HTTP接口的全局处理器：统一捕获 Controller 调用链中没有处理的异常
把异常转换成统一 HTTP JSON 响应 。
* Controller
  -> ServiceImpl
  -> throw BusinessException
  -> ApiExceptionHandler.handleBusinessException
  -> HTTP 400 + ApiResponse.failure(...)*/
@Slf4j
/*这个类被 Spring 扫描到后，会成为全局异常处理器 Bean:
所有 Controller 执行过程中，如果抛出异常且 Controller 自己没处理，
就来这里找对应的 @ExceptionHandler 方法。*/
@RestControllerAdvice
public class ApiExceptionHandler {

    /* @ExceptionHandler(BusinessException.class) :
    项目自己主动定义的业务错误，有具体 code。
    只要 Controller 链路里抛出：
    throw new BusinessException(...)
    就会进这个方法*/
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception) {

        log.warn("Business exception: code={}, message={}",
                exception.getCode(), exception.getMessage());

        /*这段是式写法意思是：
        HTTP 状态码：400 Bad Request
        响应体：ApiResponse.failure(exception.getCode(), exception.getMessage())
        HTTP 状态码表达接口层面的请求失败;
        业务 code 表达项目内部定义的具体失败原因
        */
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(
                        exception.getCode(),
                        exception.getMessage()
                ));
    }

    /*这个方法处理 Java 常见参数异常。
    比如代码里可能写：throw new IllegalArgumentException("图片上下文不能为空");
    就会进入这个地方*/
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException exception) {

        log.warn("Invalid request parameter: {}", exception.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(
                        40000,
                        exception.getMessage()));
    }

    /*@ExceptionHandler(Exception.class)
    处理所有未知异常 ，这是兜底处理器。
    只要前面两个没匹配上，其他异常都到这里。*/
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception) {

        log.error("Unexpected system exception", exception);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(
                        50000,
                        "系统繁忙，请稍后重试"));
    }
}
