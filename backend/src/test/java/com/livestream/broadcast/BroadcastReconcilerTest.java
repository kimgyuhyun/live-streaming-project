package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class BroadcastReconcilerTest {

    private BroadcastService broadcastService;
    private BroadcastReconciler reconciler;

    @BeforeEach
    void setUp() {
        broadcastService = mock(BroadcastService.class);
        reconciler = new BroadcastReconciler(broadcastService);
    }

    @Nested
    @DisplayName("BroadcastReconciler.run")
    class Run {

        @Test
        @DisplayName("should reconcile once per run")
        void reconcilesOnce() {
            reconciler.run();

            verify(broadcastService).reconcile();
        }

        @Test
        @DisplayName("should not let a failed reconcile escape the scheduler")
        void swallowsFailure() {
            willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)).given(broadcastService).reconcile();

            assertThatCode(() -> reconciler.run()).doesNotThrowAnyException();
        }
    }
}
