package com.fourth.ykd.ilink.service.impl;

import com.fourth.ykd.exception.BusinessException;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import com.fourth.ykd.ilink.config.IlinkProperties;
import com.fourth.ykd.ilink.dto.IlinkLoginQrResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginStatusResponse;
import com.fourth.ykd.ilink.service.IlinkLoginService;
import com.github.wechat.ilink.sdk.ILinkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class IlinkLoginServiceImpl implements IlinkLoginService {

    private final IlinkProperties properties;
    private final IlinkClientManager clientManager;

    @Override
    public IlinkLoginQrResponse startLogin() {
        if (!properties.isEnabled()){
            throw new BusinessException(50010,"iLink模块未启用");
        }
        ILinkClient client = clientManager.createNewClient();

        try {
            String qrCodeContent = client.executeLogin();

            /*
             * executeLogin() 返回后，扫码登录仍在异步进行。
             * getLoginFuture() 会在手机扫码且登录完成后结束。
             * 这里先记录结果，下一步再加入会话持久化逻辑。
             */
            client.getLoginFuture().whenComplete((loginContext, throwable) -> {
                if (throwable == null) {
                    log.info("[iLink] login succeeded");
                } else {
                    log.warn("[iLink] login failed: {}", throwable.getMessage());
                }
            });

            return new IlinkLoginQrResponse(
                    qrCodeContent,
                    client.getLoginStatus().getStatus().name()
            );
        } catch (Exception exception) {
            /*
             * 二维码申请失败时，这个新客户端没有继续保留的意义。
             * 立即关闭，防止线程池或网络资源残留。
             */
            clientManager.closeCurrentClient();
            throw new BusinessException(50011, "iLink 二维码登录启动失败");
        }
    }

    @Override
    public IlinkLoginStatusResponse getLoginStatus() {
        return clientManager.findClient()
                .map(client -> new IlinkLoginStatusResponse(
                        client.getLoginStatus().getStatus().name(),
                        client.isLoggedIn()
                ))
                .orElseGet(() -> new IlinkLoginStatusResponse(
                        "NOT_STARTED",
                        false
                ));
    }

    @Override
    public void cancelLogin() {
        clientManager.findClient().ifPresent(ILinkClient::cancelLogin);
        clientManager.closeCurrentClient();
    }
}
