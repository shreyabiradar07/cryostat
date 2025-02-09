/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.net.web.http;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.rmi.ConnectIOException;
import java.util.Base64;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.ServiceUnavailableException;
import javax.script.ScriptException;
import javax.security.sasl.SaslException;

import org.openjdk.jmc.rjmx.ConnectionException;

import io.cryostat.configuration.CredentialsManager;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.log.Logger;
import io.cryostat.core.net.Credentials;
import io.cryostat.net.AuthManager;
import io.cryostat.net.AuthorizationErrorException;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.PermissionDeniedException;
import io.cryostat.net.web.http.api.v2.ApiException;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import org.apache.commons.lang3.exception.ExceptionUtils;

public abstract class AbstractAuthenticatedRequestHandler implements RequestHandler {

    public static final Pattern AUTH_HEADER_PATTERN =
            Pattern.compile("(?<type>[\\w]+)[\\s]+(?<credentials>[\\S]+)");
    public static final String JMX_AUTHENTICATE_HEADER = "X-JMX-Authenticate";
    public static final String JMX_AUTHORIZATION_HEADER = "X-JMX-Authorization";

    protected final AuthManager auth;
    protected final CredentialsManager credentialsManager;
    protected final Logger logger;

    protected AbstractAuthenticatedRequestHandler(
            AuthManager auth, CredentialsManager credentialsManager, Logger logger) {
        this.auth = auth;
        this.credentialsManager = credentialsManager;
        this.logger = logger;
    }

    public abstract void handleAuthenticated(RoutingContext ctx) throws Exception;

    @Override
    public void handle(RoutingContext ctx) {
        try {
            boolean permissionGranted = validateRequestAuthorization(ctx.request()).get();
            if (!permissionGranted) {
                // expected to go into catch clause below
                throw new HttpException(401, "HTTP Authorization Failure");
            }
            // set Content-Type: text/plain by default. Handler implementations may replace this.
            ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, HttpMimeType.PLAINTEXT.mime());
            handleAuthenticated(ctx);
        } catch (ApiException | HttpException e) {
            throw e;
        } catch (Exception e) {
            if (isAuthFailure(e)) {
                throw new HttpException(401, "HTTP Authorization Failure", e);
            }
            if (isTargetConnectionFailure(e)) {
                handleConnectionException(ctx, e);
            }
            throw new HttpException(500, e.getMessage(), e);
        }
    }

    protected Future<Boolean> validateRequestAuthorization(HttpServerRequest req) throws Exception {
        return auth.validateHttpHeader(
                () -> req.getHeader(HttpHeaders.AUTHORIZATION), resourceActions());
    }

    protected ConnectionDescriptor getConnectionDescriptorFromContext(RoutingContext ctx) {
        String targetId = ctx.pathParam("targetId");
        try {
            Credentials credentials;
            if (ctx.request().headers().contains(JMX_AUTHORIZATION_HEADER)) {
                String proxyAuth = ctx.request().getHeader(JMX_AUTHORIZATION_HEADER);
                Matcher m = AUTH_HEADER_PATTERN.matcher(proxyAuth);
                if (!m.find()) {
                    ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                    throw new HttpException(427, "Invalid " + JMX_AUTHORIZATION_HEADER + " format");
                }
                String t = m.group("type");
                if (!"basic".equals(t.toLowerCase())) {
                    ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                    throw new HttpException(
                            427, "Unacceptable " + JMX_AUTHORIZATION_HEADER + " type");
                }
                String c;
                try {
                    c =
                            new String(
                                    Base64.getUrlDecoder().decode(m.group("credentials")),
                                    StandardCharsets.UTF_8);
                } catch (IllegalArgumentException iae) {
                    ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                    throw new HttpException(
                            427,
                            JMX_AUTHORIZATION_HEADER
                                    + " credentials do not appear to be Base64-encoded",
                            iae);
                }
                String[] parts = c.split(":");
                if (parts.length != 2) {
                    ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
                    throw new HttpException(
                            427, "Unrecognized " + JMX_AUTHORIZATION_HEADER + " credential format");
                }
                credentials = new Credentials(parts[0], parts[1]);
            } else {
                credentials = credentialsManager.getCredentialsByTargetId(targetId);
            }
            return new ConnectionDescriptor(targetId, credentials);
        } catch (ScriptException e) {
            throw new HttpException(500, e);
        }
    }

    public static boolean isTargetConnectionFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, ConnectionException.class) >= 0
                || ExceptionUtils.indexOfType(e, FlightRecorderException.class) >= 0;
    }

    public static boolean isAuthFailure(Exception e) {
        // Check if the Exception has a PermissionDeniedException or KubernetesClientException
        // in its cause chain
        return ExceptionUtils.indexOfType(e, PermissionDeniedException.class) >= 0
                || ExceptionUtils.indexOfType(e, AuthorizationErrorException.class) >= 0
                || ExceptionUtils.indexOfType(e, KubernetesClientException.class) >= 0;
    }

    public static boolean isJmxAuthFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, SecurityException.class) >= 0
                || ExceptionUtils.indexOfType(e, SaslException.class) >= 0;
    }

    public static boolean isJmxSslFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, ConnectIOException.class) >= 0
                && !isServiceTypeFailure(e);
    }

    /** Check if the exception happened because the port connected to a non-JMX service. */
    public static boolean isServiceTypeFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, ConnectIOException.class) >= 0
                && ExceptionUtils.indexOfType(e, SocketTimeoutException.class) >= 0;
    }

    public static boolean isUnknownTargetFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, UnknownHostException.class) >= 0
                || ExceptionUtils.indexOfType(e, ServiceUnavailableException.class) >= 0;
    }

    private void handleConnectionException(RoutingContext ctx, Exception e) {
        if (isJmxAuthFailure(e)) {
            ctx.response().putHeader(JMX_AUTHENTICATE_HEADER, "Basic");
            throw new HttpException(427, "JMX Authentication Failure", e);
        }
        if (isUnknownTargetFailure(e)) {
            throw new HttpException(404, "Target Not Found", e);
        }
        if (isJmxSslFailure(e)) {
            throw new HttpException(502, "Target SSL Untrusted", e);
        }
        if (isServiceTypeFailure(e)) {
            throw new HttpException(504, "Non-JMX Port", e);
        }
    }
}
