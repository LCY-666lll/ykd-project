package com.fourth.ykd.ilink.service;

import com.github.wechat.ilink.sdk.ILinkClient;

public interface IlinkMessageReplyService {

    void submit(ILinkClient client, String userId, String contextToken, String userText);

    void submitImageReceived(ILinkClient client, String userId);

    void submitVoice(ILinkClient client, String userId, String contextToken, String voiceText);

    void submitVoiceRecognitionFailed(ILinkClient client, String userId);

    void reply(String userId, String content);
}
