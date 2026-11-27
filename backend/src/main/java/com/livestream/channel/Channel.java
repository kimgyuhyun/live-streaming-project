package com.livestream.channel;

public class Channel {

    private Long id;
    private long memberId;
    private String name;
    // Oracle은 빈 문자열을 NULL로 저장한다. 빈 소개는 NULL로 읽힌다
    private String description;
    // 스트림 키의 SHA-256 해시(소문자 16진수)와 앞 4자리. 키가 없으면 null (PRD channel C1)
    private String streamKeyHash;
    private String streamKeyPrefix;
    // 진행 중 방송(broadcast.ended_at IS NULL)이 있으면 true. 조회 SQL이 함께 읽는다
    private boolean live;
    // 방송 제목. 정한 적 없으면 null
    private String title;
    // 운영자가 데모 표시를 붙였으면 true(#14)
    private boolean demo;
    // 팔로워 수(#37). 팔로우가 실제로 들어가거나 지워질 때만 ±1
    private long followerCount;
    // 진행 중 방송의 id(#39). 방송 중이 아니면 null. 조회 SQL이 함께 읽는다
    private Long liveBroadcastId;

    public Channel() {
    }

    public Channel(long memberId, String name, String description) {
        this.memberId = memberId;
        this.name = name;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getMemberId() {
        return memberId;
    }

    public void setMemberId(long memberId) {
        this.memberId = memberId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStreamKeyHash() {
        return streamKeyHash;
    }

    public void setStreamKeyHash(String streamKeyHash) {
        this.streamKeyHash = streamKeyHash;
    }

    public String getStreamKeyPrefix() {
        return streamKeyPrefix;
    }

    public void setStreamKeyPrefix(String streamKeyPrefix) {
        this.streamKeyPrefix = streamKeyPrefix;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    public long getFollowerCount() {
        return followerCount;
    }

    public void setFollowerCount(long followerCount) {
        this.followerCount = followerCount;
    }

    public Long getLiveBroadcastId() {
        return liveBroadcastId;
    }

    public void setLiveBroadcastId(Long liveBroadcastId) {
        this.liveBroadcastId = liveBroadcastId;
    }
}
