package com.livestream.follow;

import java.util.List;

// GET /api/me/following 응답(#38). 20개씩, 다음 묶음이 있으면 hasMore
public record FollowingResponse(List<FollowingChannel> items, boolean hasMore) {
}
