/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.tomitribe.tribestream.registryng.security;

import org.tomitribe.tribestream.registryng.entities.AccessToken;
import org.tomitribe.tribestream.registryng.security.oauth2.AccessTokenService;

import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

@WebFilter(urlPatterns = "/api/*")
public class SecurityWebFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(SecurityWebFilter.class.getName());

    @Inject
    private AccessTokenService accessTokenService;

    /**
     * Contains all request URIs that are available without authentication.
     * Everything that is not under /api is available because the filter does not apply for these requests.
     */
    private Set<String> urlWhiteList;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        urlWhiteList = Stream.of("/api/server/info", "/api/login", "/api/security/oauth2")
                .map(p -> filterConfig.getServletContext().getContextPath() + p)
                .collect(toSet());
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;

        if (isSecuredPath(httpServletRequest)) {

            final String authHeader = httpServletRequest.getHeader("Authorization");
            if (authHeader == null) {
                LOGGER.log(Level.FINE, "No Authorization header");
                sendUnauthorizedResponse(servletResponse);
                return;
            }

            if (authHeader.startsWith("Basic ")) {

                if (loginBasic(httpServletRequest, authHeader)) {

                    filterChain.doFilter(servletRequest, servletResponse);

                    logoutBasic(httpServletRequest);
                } else {
                    sendUnauthorizedResponse(servletResponse);
                }

            } else if (authHeader.startsWith("Bearer ")) {

                AccessToken accessToken = accessTokenService.getToken(authHeader.substring("Bearer ".length()));
                if (accessToken != null) {
                    filterChain.doFilter(servletRequest, servletResponse);
                } else {
                    LOGGER.log(Level.INFO, "Did not find submitted access token");
                    sendUnauthorizedResponse(servletResponse);
                }

            } else {
                LOGGER.log(Level.FINE, "Unsupported authorization header");
                sendUnauthorizedResponse(servletResponse);
            }
        } else {
            LOGGER.fine(() -> "Request to " + httpServletRequest.getRequestURI() + " is not secured.");
            filterChain.doFilter(servletRequest, servletResponse);

        }
    }

    private void logoutBasic(HttpServletRequest httpServletRequest) throws ServletException {
        httpServletRequest.logout();
    }

    private boolean loginBasic(HttpServletRequest httpServletRequest, String authHeader) {

        final String encodedToken = authHeader.substring("Basic ".length());
        final String clearToken = new String(Base64.getDecoder().decode(encodedToken), StandardCharsets.UTF_8);
        final String[] userPassword = clearToken.split(":");
        if (userPassword.length != 2) {
            return false;
        }
        final String username = userPassword[0];
        final String password = userPassword[1];
        try {
            httpServletRequest.login(username, password);
            return true;
        } catch (ServletException e) {
            LOGGER.log(Level.WARNING, e, () -> String.format("Login failed for user %s", username));
            return false;
        }
    }

    private boolean isSecuredPath(HttpServletRequest httpServletRequest) {
        return !urlWhiteList.contains(httpServletRequest.getRequestURI());
    }

    private void sendUnauthorizedResponse(ServletResponse servletResponse) throws IOException {
        HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
        httpServletResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Override
    public void destroy() {

    }
}
