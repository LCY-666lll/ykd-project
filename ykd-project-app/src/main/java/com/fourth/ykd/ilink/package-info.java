/*7.17 Spring 定时任务
    ↓
每隔 500ms 调用 pollMessages()
    ↓
从 IlinkClientManager 获取已经登录的微信客户端
    ↓
client.getUpdates() 拉取微信消息
    ↓
提取消息里的文本
    ↓
排除空消息、图片消息、机器人自己发送的消息
    ↓
调用 ilinkMessageReplyService.submit()
    ↓
消息进入全局 CompletableFuture 链
    ↓
等待前一条消息处理完成
    ↓
在线程池中执行 reply()
    ↓
client.startTyping() 显示“正在输入”
        ↓
aiChatService.chat() 校验参数
    ↓
deepSeekClient.chat() 请求 DeepSeek
    ↓
解析 DeepSeek 返回的回答
    ↓
client.sendText() 把回答发回微信
    ↓
client.stopTyping() 停止“正在输入”
        ↓
当前任务完成，下一条任务开始*/
package com.fourth.ykd.ilink;