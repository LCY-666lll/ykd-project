package com.fourth.ykd.ilink.service;

import com.fourth.ykd.ilink.dto.IlinkLoginQrResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginStatusResponse;

public interface IlinkLoginService {

    /**
     * 创建新客户端并向 iLink 服务请求二维码内容。
     */
    IlinkLoginQrResponse startLogin();

    /**
     * 查询当前客户端的登录状态。
     */
    IlinkLoginStatusResponse getLoginStatus();

    /**
     * 取消当前扫码登录，并释放客户端资源。
     */
    void cancelLogin();
}