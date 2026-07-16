package com.fourth.ykd.ilink.service;

import com.fourth.ykd.ilink.dto.IlinkLoginQrResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginStatusResponse;

public interface IlinkLoginService {
    IlinkLoginQrResponse startLogin();
    IlinkLoginStatusResponse getLoginStatus();
    void cancelLogin();
}
