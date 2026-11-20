package com.livestream.member;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// SQL은 resources/mapper/member/MemberMapper.xml
@Mapper
public interface MemberMapper {

    // 생성된 id를 member에 채운다
    void insert(Member member);

    // 소문자 이메일로 찾는다
    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    Optional<Member> findByEmail(String email);

    Optional<Member> findById(long id);

    void updateNickname(@Param("id") long id, @Param("nickname") String nickname);

    void updatePasswordHash(@Param("id") long id, @Param("passwordHash") String passwordHash);
}
