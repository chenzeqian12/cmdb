package com.cloudwise.cmdb.integration.servicenow.dto;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SnGroupMember#isValid()} 单元测试。
 *
 * <p>重点锁住「有 user_name 无 email 也算有效」这一条 —— 这个口径曾经是要求邮箱必填，
 * 导致这 26 条记录被当脏数据丢掉、匹配面白白缩小，而测试里统计出来的条数
 * 又和服务端实际丢弃的条数对不上。用测试钉死，避免再次漂移。</p>
 *
 * @author hkex-cmdb-sync
 */
class SnGroupMemberTest {

    private static SnGroupMember member(String group, String email, String userName) {
        SnGroupMember member = new SnGroupMember();
        member.setGroup(group);
        member.setUserEmail(email);
        member.setUserName(userName);
        return member;
    }

    @Test
    @DisplayName("组名与邮箱都有：有效")
    void validWithGroupAndEmail() {
        Assertions.assertThat(member("A_SUP", "a@x.com", null).isValid()).isTrue();
    }

    @Test
    @DisplayName("有组名与 user_name、没有邮箱：仍然有效（曾经被误判为脏数据）")
    void validWithGroupAndUserNameOnly() {
        Assertions.assertThat(member("A_SUP", null, "alice").isValid()).isTrue();
        Assertions.assertThat(member("A_SUP", "", "alice").isValid()).isTrue();
    }

    @Test
    @DisplayName("没了组名就没意义：无效")
    void invalidWithoutGroup() {
        Assertions.assertThat(member(null, "a@x.com", "alice").isValid()).isFalse();
        Assertions.assertThat(member("   ", "a@x.com", "alice").isValid()).isFalse();
    }

    @Test
    @DisplayName("组名在、两个标识都空：无效（SN 里用户被删时会返回空串）")
    void invalidWithoutAnyIdentifier() {
        Assertions.assertThat(member("A_SUP", null, null).isValid()).isFalse();
        Assertions.assertThat(member("A_SUP", "", "  ").isValid()).isFalse();
    }
}
