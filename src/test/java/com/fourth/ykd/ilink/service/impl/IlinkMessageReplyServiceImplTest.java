package com.fourth.ykd.ilink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.routing.UserIntent;
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
                    replyProcessor, replySender, executor);

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
                replyProcessor, replySender, Runnable::run);

        when(replyProcessor.process(USER_ID, "first", false))
                .thenReturn(IlinkReplyProcessor.ReplyResult.text(UserIntent.TEXT, "first answer", null));

        service.submit(client, USER_ID, "first");

        verify(replySender, never()).sendQueueWaitingMessage(client, USER_ID);
    }
}
