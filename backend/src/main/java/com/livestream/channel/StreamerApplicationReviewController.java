package com.livestream.channel;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operator/streamer-applications")
class StreamerApplicationReviewController {

    StreamerApplicationReviewController(StreamerApplicationReviewService reviewService) {
    }

    @GetMapping
    List<PendingApplicationResponse> pending() {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/{id}/approve")
    StreamerApplicationResponse approve(@PathVariable long id) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/{id}/reject")
    StreamerApplicationResponse reject(@PathVariable long id) {
        throw new UnsupportedOperationException("not implemented");
    }
}
