// com/example/demo/entity/ResponseResult.java
package com.example.demo.entity;

import lombok.Data;

@Data
public class ResponseResult<T> {
    // 状态码：200成功，500失败
    private int code;
    // 响应消息
    private String msg;
    // 响应数据（分类结果、任务信息等）
    private T data;

    // 成功响应（带数据）
    public static <T> ResponseResult<T> success(T data, String msg) {
        ResponseResult<T> result = new ResponseResult<>();
        result.setCode(200);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }

    // 成功响应（默认消息）
    public static <T> ResponseResult<T> success(T data) {
        return success(data, "操作成功");
    }

    // 失败响应
    public static <T> ResponseResult<T> fail(String msg) {
        ResponseResult<T> result = new ResponseResult<>();
        result.setCode(500);
        result.setMsg(msg);
        result.setData(null);
        return result;
    }
}