package com.livestream.channel;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 운영자만 부른다(SecurityConfig의 /api/operator/**). 화면 없는 API다(PRD channel "하지 않을 것")
@RestController
@RequestMapping("/api/operator/streamer-applications")
class StreamerApplicationReviewController {

    private final StreamerApplicationReviewService reviewService;

    StreamerApplicationReviewController(StreamerApplicationReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    List<PendingApplicationResponse> pending() {
        return reviewService.findPending();
    }

    @PostMapping("/{id}/approve")
    StreamerApplicationResponse approve(@PathVariable long id) {
        return reviewService.approve(id);
    }

    @PostMapping("/{id}/reject")
    StreamerApplicationResponse reject(@PathVariable long id) {
        return reviewService.reject(id);
    }
}
