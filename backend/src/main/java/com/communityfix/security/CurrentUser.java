package com.communityfix.security;

import com.communityfix.common.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Set;

public record CurrentUser(long id, String nickname, Set<String> roles, Set<String> permissions, Set<Long> communities, Long workerId) {
    public boolean hasRole(String role) { return roles.contains(role); }
    public void role(String role) { if(!hasRole(role)) throw ApiException.forbidden(); }
    public void permission(String permission) { if(!permissions.contains(permission)) throw ApiException.forbidden(); }
    public void community(long id) { role("ADMIN"); if(!communities.contains(id)) throw ApiException.forbidden(); }
    public static CurrentUser get() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof CurrentUser user)) throw new ApiException(401,"UNAUTHENTICATED","请先登录");
        return user;
    }
}
