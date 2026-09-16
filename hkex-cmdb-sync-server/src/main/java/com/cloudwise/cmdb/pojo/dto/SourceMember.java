package com.cloudwise.cmdb.pojo.dto;

import com.cloudwise.cmdb.integration.servicenow.dto.SnGroupMember;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 参与同步的一个源成员，以及它可用于匹配 DOUC 用户的候选键。
 *
 * <p>SN 的组员记录同时带 {@code user.email} 与 {@code user.user_name}，
 * 而 DOUC 侧只有 {@code userAlias} 可从接口读到（{@code User.code} 存在于模型
 * 但未在 {@code UserListResponseObject} 中暴露，{@code /user/get/{id}} 又返回加密串）。
 * 因此两个字段都拿去试，命中哪个算哪个——比只用 email 的匹配面宽得多。</p>
 *
 * @author hkex-cmdb-sync
 */
@Getter
@ToString(of = {"email", "userName"})
@EqualsAndHashCode
public class SourceMember {

    /** SN 的 user.email。 */
    private final String email;

    /** SN 的 user.user_name。 */
    private final String userName;

    private SourceMember(String email, String userName) {
        this.email = email;
        this.userName = userName;
    }

    /**
     * 从 SN 组员记录构造，组名或两个标识都为空时返回 null（脏记录）。
     *
     * @param member SN 组员记录
     * @return 源成员，不可用时为 null
     */
    public static SourceMember from(SnGroupMember member) {
        if (member == null || !StringUtils.hasText(member.getGroup())) {
            return null;
        }
        String email = normalize(member.getUserEmail());
        String userName = normalize(member.getUserName());
        if (!StringUtils.hasText(email) && !StringUtils.hasText(userName)) {
            return null;
        }
        return new SourceMember(email, userName);
    }

    /**
     * 匹配 DOUC 用户时依次尝试的键，按优先级排列且已去重。
     *
     * <p>email 放前面：DOUC 侧 {@code userAlias} 常见填邮箱。</p>
     *
     * @return 候选键列表，可能为空
     */
    public List<String> candidateKeys() {
        List<String> keys = new ArrayList<>(2);
        if (StringUtils.hasText(email)) {
            keys.add(email);
        }
        if (StringUtils.hasText(userName) && !keys.contains(userName)) {
            keys.add(userName);
        }
        return keys;
    }

    /** 日志与结果里展示用的标识，优先邮箱。 */
    public String displayKey() {
        return StringUtils.hasText(email) ? email : userName;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
