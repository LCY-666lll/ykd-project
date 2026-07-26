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
/*创建 iLink client，调用 client.executeLogin() 获取二维码内容，监听登录结果，取消登录状态*/

@Slf4j
@Service
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

            /*executeLogin() 完成的是：
            客户端向 iLink 服务发起登录请求
            → iLink 返回二维码原始内容
            → 方法返回字符串*/
            String qrCodeContent = client.executeLogin();

            /*
             client.getLoginFuture()：它代表一个未来才会完成的登录结果。
             现在还没有登录结果
             → 先拿到一个 Future → 用户扫码完成后 Future 成功 → 登录失败或取消后 Future 异常完成
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
                //Optional 中有客户端 → 把 ILinkClient 转换成 IlinkLoginStatusResponse
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

        try{
            //通知 SDK：终止当前正在进行的扫码登录流程
            clientManager.findClient().ifPresent(ILinkClient::cancelLogin);
        }finally {
            clientManager.closeCurrentClient();
        }
    }
}
