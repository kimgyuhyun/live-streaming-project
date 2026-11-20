package com.livestream.channel;

public class StreamerApplication {

    private Long id;
    private long memberId;
    private ApplicationStatus status;

    public StreamerApplication() {
    }

    public StreamerApplication(long memberId, ApplicationStatus status) {
        this.memberId = memberId;
        this.status = status;
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

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }
}
