package com.communityfix.security;

import com.communityfix.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class JwtFilter extends OncePerRequestFilter {
    // Deferred support responses resume on another servlet thread; authenticate that dispatch too.
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    private final TokenService tokens; private final IdentityService identities; private final ObjectMapper json;
    public JwtFilter(TokenService tokens,IdentityService identities,ObjectMapper json) { this.tokens=tokens; this.identities=identities; this.json=json; }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String header=request.getHeader("Authorization");
        if(header!=null && header.startsWith("Bearer ")) {
            try {
                var id=tokens.parse(header.substring(7)); var user=identities.load(id.userId(),id.version());
                var authorities=user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,authorities));
            } catch(io.jsonwebtoken.JwtException | IllegalArgumentException | ApiException ex) {
                response.setStatus(401); response.setContentType("application/json;charset=UTF-8"); json.writeValue(response.getWriter(),ApiResponse.error(401,"SESSION_EXPIRED","登录已失效，请重新登录")); return;
            } catch(Exception ex) {
                response.setStatus(503); response.setContentType("application/json;charset=UTF-8"); json.writeValue(response.getWriter(),ApiResponse.error(503,"AUTH_UNAVAILABLE","认证服务暂时不可用")); return;
            }
        }
        chain.doFilter(request,response);
    }
}
