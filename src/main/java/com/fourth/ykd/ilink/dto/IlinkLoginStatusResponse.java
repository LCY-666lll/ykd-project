package com.fourth.ykd.ilink.dto;

/**
 * 当前 iLink 登录状态。
 * status：SDK 的状态名，例如 WAITING、SCANNED、LOGGED_IN、EXPIRED。
 * loggedIn：项目业务层更容易判断的 true / false。
 */
public record IlinkLoginStatusResponse(
        String status,
        boolean loggedIn
) {
}