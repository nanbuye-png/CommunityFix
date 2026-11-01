package com.communityfix.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Service
public class TokenService {
    private final SecretKey key; private final Clock clock; private final String issuer,audience;
    public TokenService(@Value("${communityfix.jwt-secret}") String secret,@Value("${communityfix.jwt-issuer}") String issuer,@Value("${communityfix.jwt-audience}") String audience,Clock clock) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.issuer=issuer; this.audience=audience; this.clock=clock;
    }
    public String issue(long userId,int version) { return Jwts.builder().subject(Long.toString(userId)).issuer(issuer).audience().add(audience).and().claim("ver",version).issuedAt(Date.from(clock.instant())).expiration(Date.from(expiresAt())).signWith(key,Jwts.SIG.HS256).compact(); }
    public Instant expiresAt() { return clock.instant().plusSeconds(7200); }
    public Identity parse(String token) {
        var jwt=Jwts.parser().verifyWith(key).requireIssuer(issuer).requireAudience(audience).clock(()->Date.from(clock.instant())).build().parseSignedClaims(token);
        if(!"HS256".equals(jwt.getHeader().getAlgorithm())) throw new IllegalArgumentException("Unsupported algorithm");
        return new Identity(Long.parseLong(jwt.getPayload().getSubject()),jwt.getPayload().get("ver",Integer.class));
    }
    public record Identity(long userId,int version) {}
}
