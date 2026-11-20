package com.livestream.channel;

public class Channel {

    private Long id;
    private long memberId;
    private String name;
    // Oracle은 빈 문자열을 NULL로 저장한다. 빈 소개는 NULL로 읽힌다
    private String description;

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
}
