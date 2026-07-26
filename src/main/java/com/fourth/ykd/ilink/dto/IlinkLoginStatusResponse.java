package com.fourth.ykd.ilink.dto;

/**
 * 当前 iLink 登录状态。
 * status：SDK 的状态名，例如 WAITING、SCANNED、LOGGED_IN、EXPIRED。
 * loggedIn：项目业务层更容易判断的 true / false。
 * status
 * → 展示具体过程和排查问题
 * loggedIn
 * → 业务判断是否登录完成
 */
public record IlinkLoginStatusResponse(
        String status,
        boolean loggedIn
) {
}