package com.fourth.ykd.ilink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AiChatService;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IlinkMessageReplyServiceImplTest {

    private static final String USER_ID = "wechat-user";

    @Test
    void shouldKeepLaterMessageWaitingUntilEarlierReplyFinishes() throws Exception {
        IlinkReplyProcessor replyProcessor = mock(IlinkReplyProcessor.class);
        IlinkReplySender replySender = mock(IlinkReplySender.class);
        ILinkClient client = mock(ILinkClient.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try {
            IlinkMessageReplyServiceImpl service = new IlinkMessageReplyServiceImpl(
                    replyProcessor, replySender, executor, mock(AiChatService.class), Runnable::run);

            when(replyProcessor.process(USER_ID, "first", false)).thenAnswer(invocation -> {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("first reply was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return IlinkReplyProcessor.ReplyResult.text(UserIntent.TEXT, "first answer", null);
            });
            when(replyProcessor.process(USER_ID, "second", false)).thenAnswer(invocation -> {
                secondStarted.countDown();
                return IlinkReplyProcessor.ReplyResult.text(UserIntent.TEXT, "second answer", null);
            });

            service.submit(client, USER_ID, "first");
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            service.submit(client, USER_ID, "second");
            assertThat(secondStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
            verify(replySender).sendQueueWaitingMessage(client, USER_ID);

            releaseFirst.countDown();
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldNotSendQueueWaitingMessageForFirstTask() {
        IlinkReplyProcessor replyProcessor = mock(IlinkReplyProcessor.class);
        IlinkReplySender replySender = mock(IlinkReplySender.class);
        ILinkClient client = mock(ILinkClient.class);
        IlinkMessageReplyServiceImpl service = new IlinkMessageReplyServiceImpl(
                replyProcessor, replySender, Runnable::run, mock(AiChatService.class), Runnable::run);

        when(replyProcessor.process(USER_ID, "first", false))
                .thenReturn(IlinkReplyProcessor.ReplyResult.text(UserIntent.TEXT, "first answer", null));

        service.submit(client, USER_ID, "first");

        verify(replySender, never()).sendQueueWaitingMessage(client, USER_ID);
    }
    @Test
    void shouldRunMemoryManagementAfterSendingReceipt() throws Exception {
        IlinkReplyProcessor replyProcessor = mock(IlinkReplyProcessor.class);
        IlinkReplySender replySender = mock(IlinkReplySender.class);
        AiChatService aiChatService = mock(AiChatService.class);
        ILinkClient client = mock(ILinkClient.class);
        ExecutorService replyExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch receiptSent = new CountDownLatch(1);
        CountDownLatch completionSent = new CountDownLatch(1);

        try {
            IlinkMessageReplyServiceImpl service = new IlinkMessageReplyServiceImpl(
                    replyProcessor, replySender, replyExecutor, aiChatService, Runnable::run);
            IlinkReplyProcessor.ReplyResult receipt = IlinkReplyProcessor.ReplyResult.text(
                    UserIntent.MEMORY_MANAGE,
                    "收到，正在处理你的长期记忆请求，完成后通知你。",
                    null
            );
            when(replyProcessor.process(USER_ID, "记住我叫小李", false)).thenReturn(receipt);
            when(aiChatService.manageMemory(USER_ID, "记住我叫小李"))
                    .thenReturn(new AiChatResponse("已记住你的称呼。"));
            org.mockito.Mockito.doAnswer(invocation -> {
                IlinkReplyProcessor.ReplyResult result = invocation.getArgument(2);
                if ("收到，正在处理你的长期记忆请求，完成后通知你。".equals(result.answer())) {
                    receiptSent.countDown();
                }
                if ("已记住你的称呼。".equals(result.answer())) {
                    completionSent.countDown();
                }
                return null;
            }).when(replySender).sendTextModeReply(
                    org.mockito.ArgumentMatchers.eq(client),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong()
            );

            service.submit(client, USER_ID, "记住我叫小李");

            assertThat(receiptSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(completionSent.await(1, TimeUnit.SECONDS)).isTrue();
            verify(aiChatService).manageMemory(USER_ID, "记住我叫小李");
        } finally {
            replyExecutor.shutdownNow();
            replyExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldRunMemoryManagementAfterVoiceReceipt() throws Exception {
        IlinkReplyProcessor replyProcessor = mock(IlinkReplyProcessor.class);
        IlinkReplySender replySender = mock(IlinkReplySender.class);
        AiChatService aiChatService = mock(AiChatService.class);
        ILinkClient client = mock(ILinkClient.class);
        ExecutorService replyExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch receiptSent = new CountDownLatch(1);
        CountDownLatch completionSent = new CountDownLatch(1);

        try {
            IlinkMessageReplyServiceImpl service = new IlinkMessageReplyServiceImpl(
                    replyProcessor, replySender, replyExecutor, aiChatService, Runnable::run);
            IlinkReplyProcessor.ReplyResult receipt = IlinkReplyProcessor.ReplyResult.text(
                    UserIntent.MEMORY_MANAGE, "receipt", null);
            when(replyProcessor.process(USER_ID, "close project", true)).thenReturn(receipt);
            when(aiChatService.manageMemory(USER_ID, "close project"))
                    .thenReturn(new AiChatResponse("completion"));
            org.mockito.Mockito.doAnswer(invocation -> {
                receiptSent.countDown();
                return null;
            }).when(replySender).sendVoiceModeReply(
                    org.mockito.ArgumentMatchers.eq(client),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    org.mockito.ArgumentMatchers.eq(receipt),
                    org.mockito.ArgumentMatchers.anyLong()
            );
            org.mockito.Mockito.doAnswer(invocation -> {
                IlinkReplyProcessor.ReplyResult result = invocation.getArgument(2);
                if ("completion".equals(result.answer())) {
                    completionSent.countDown();
                }
                return null;
            }).when(replySender).sendTextModeReply(
                    org.mockito.ArgumentMatchers.eq(client),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong()
            );

            service.submitVoice(client, USER_ID, "close project");

            assertThat(receiptSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(completionSent.await(1, TimeUnit.SECONDS)).isTrue();
            verify(aiChatService).manageMemory(USER_ID, "close project");
        } finally {
            replyExecutor.shutdownNow();
            replyExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }}
