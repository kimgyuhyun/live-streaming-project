package com.livestream.member;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
