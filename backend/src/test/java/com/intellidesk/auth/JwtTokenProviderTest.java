package com.intellidesk.auth;

import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtTokenProviderTest {
    private final String signingFixture = UUID.randomUUID().toString() + UUID.randomUUID();
    private final JwtTokenProvider provider = new JwtTokenProvider(signingFixture, 60000);
    @AfterEach void clean() { SecurityContextHolder.clearContext(); }

    @Test void validAccessTokenExposesOnlyRequiredIdentity() {
        String token = provider.generateAccessToken(1L, "synthetic-user");
        assertThat(provider.parseAccessToken(token)).isEqualTo(new JwtTokenProvider.AccessIdentity(1L,"synthetic-user"));
        assertThat(provider.parseToken(token)).doesNotContainKeys("password","secret","authorities");
    }
    @Test void expiredTokenIsRejected() {
        assertThat(provider.parseAccessToken(new JwtTokenProvider(signingFixture,-1000).generateAccessToken(1L,"user"))).isNull();
    }
    @Test void wrongSignatureIsRejected() {
        String token = new JwtTokenProvider(UUID.randomUUID().toString()+UUID.randomUUID(),60000).generateAccessToken(1L,"user");
        assertThat(provider.parseAccessToken(token)).isNull();
    }
    @Test void malformedEmptyAndMissingRequiredClaimsAreRejected() {
        assertThat(provider.parseAccessToken("malformed.synthetic.fixture")).isNull();
        assertThat(provider.parseAccessToken("")).isNull();
        String token = Jwts.builder().subject("user").claim("type","access")
                .expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(signingFixture.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(provider.parseAccessToken(token)).isNull();
    }
    @Test void validJwtResolvesCurrentDatabaseAuthorities() throws Exception {
        var users = mock(UserService.class); var user = new User(); user.setStatus(1);
        when(users.getById(1L)).thenReturn(user);
        when(users.getRoleCodes(1L)).thenReturn(List.of("MEMBER"));
        when(users.getPermissionCodes(1L)).thenReturn(List.of("kb:read"));
        var request = new MockHttpServletRequest(); request.addHeader("Authorization","Bearer "+provider.generateAccessToken(1L,"user"));
        new JwtAuthenticationFilter(provider,users).doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("ROLE_MEMBER","kb:read");
    }
    @Test void invalidJwtNeverQueriesUserDatabase() throws Exception {
        var users = mock(UserService.class); var request = new MockHttpServletRequest();
        String invalidFixture = "malformed.synthetic.fixture";
        request.addHeader("Authorization","Bearer " + invalidFixture);
        new JwtAuthenticationFilter(provider,users).doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        verifyNoInteractions(users);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
