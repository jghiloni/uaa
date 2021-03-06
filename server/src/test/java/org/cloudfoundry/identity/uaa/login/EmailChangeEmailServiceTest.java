/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.login;

import static org.cloudfoundry.identity.uaa.account.EmailChangeEmailService.CHANGE_EMAIL_REDIRECT_URL;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCodeStore;
import org.cloudfoundry.identity.uaa.error.UaaException;
import org.cloudfoundry.identity.uaa.login.test.ThymeleafConfig;
import org.cloudfoundry.identity.uaa.message.EmailService;
import org.cloudfoundry.identity.uaa.message.MessageService;
import org.cloudfoundry.identity.uaa.message.MessageType;
import org.cloudfoundry.identity.uaa.account.EmailChangeEmailService;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.endpoints.ChangeEmailEndpoints;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.UaaUrlUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.spring4.SpringTemplateEngine;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = ThymeleafConfig.class)
public class EmailChangeEmailServiceTest {
    private EmailChangeEmailService emailChangeEmailService;
    private ChangeEmailEndpoints endpoints;
    private ScimUserProvisioning scimUserProvisioning;
    private ExpiringCodeStore codeStore;
    private MessageService messageService;
    private MockHttpServletRequest request;
    private UaaUrlUtils uaaUrlUtils;
    private ClientDetailsService clientDetailsService;


    @Autowired
    @Qualifier("mailTemplateEngine")
    SpringTemplateEngine templateEngine;

    @After
    public void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        IdentityZoneHolder.clear();
    }

    @Before
    public void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        endpoints = mock(ChangeEmailEndpoints.class);
        scimUserProvisioning = mock(ScimUserProvisioning.class);
        codeStore = mock(ExpiringCodeStore.class);
        clientDetailsService = mock(ClientDetailsService.class);
        messageService = mock(EmailService.class);
        uaaUrlUtils = new UaaUrlUtils();
        emailChangeEmailService = new EmailChangeEmailService(templateEngine, messageService, scimUserProvisioning, uaaUrlUtils, "pivotal", codeStore, clientDetailsService);

        request = new MockHttpServletRequest();
        request.setProtocol("http");
        request.setContextPath("/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    public void beginEmailChange() throws Exception {
        setUpForBeginEmailChange();

        Mockito.verify(messageService).sendMessage(
                eq("new@example.com"),
                eq(MessageType.CHANGE_EMAIL),
                eq("Pivotal Email change verification"),
                contains("<a href=\"http://localhost/login/verify_email?code=the_secret_code\">Verify your email</a>")
        );
    }

    @Test(expected = UaaException.class)
    public void beginEmailChangeWithUsernameConflict() throws Exception {
        ScimUser user = new ScimUser("user-001", "user@example.com", "test-name", "test-name");
        user.setPrimaryEmail("user@example.com");
        when(scimUserProvisioning.retrieve(anyString())).thenReturn(user);
        when(scimUserProvisioning.query(anyString())).thenReturn(Collections.singletonList(new ScimUser()));

        emailChangeEmailService.beginEmailChange("user-001", "user@example.com", "new@example.com", null, null);
    }

    @Test
    public void testBeginEmailChangeWithOssBrand() throws Exception {
        emailChangeEmailService = new EmailChangeEmailService(templateEngine, messageService, scimUserProvisioning, uaaUrlUtils, "oss", codeStore, clientDetailsService);

        setUpForBeginEmailChange();

        ArgumentCaptor<String> emailBodyArgument = ArgumentCaptor.forClass(String.class);
        Mockito.verify(messageService).sendMessage(
            eq("new@example.com"),
            eq(MessageType.CHANGE_EMAIL),
            eq("Account Email change verification"),
            emailBodyArgument.capture()
        );

        String emailBody = emailBodyArgument.getValue();

        assertThat(emailBody, containsString("<a href=\"http://localhost/login/verify_email?code=the_secret_code\">Verify your email</a>"));
        assertThat(emailBody, containsString("an account"));
        assertThat(emailBody, containsString("Cloud Foundry"));
        assertThat(emailBody, not(containsString("a Pivotal ID")));
    }

    @Test
    public void testBeginEmailChangeInOtherZone() throws Exception {
        IdentityZoneHolder.set(MultitenancyFixture.identityZone("test-zone-id", "test"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("test.localhost");
        request.setContextPath("/login");
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);

        setUpForBeginEmailChange();

        ArgumentCaptor<String> emailBodyArgument = ArgumentCaptor.forClass(String.class);
        Mockito.verify(messageService).sendMessage(
                eq("new@example.com"),
                eq(MessageType.CHANGE_EMAIL),
                eq("The Twiglet Zone Email change verification"),
                emailBodyArgument.capture()
        );

        String emailBody = emailBodyArgument.getValue();

        assertThat(emailBody, containsString(String.format("A request has been made to change the email for %s from %s to %s", "The Twiglet Zone", "user@example.com", "new@example.com")));
        assertThat(emailBody, containsString("<a href=\"http://test.localhost/login/verify_email?code=the_secret_code\">Verify your email</a>"));
        assertThat(emailBody, containsString("Thank you,<br />\n    The Twiglet Zone"));
    }

    @Test
    public void testCompleteVerification() throws Exception {
        Map<String, String> response = setUpCompleteActivation("user-name", "app", "http://app.com/redirect");
        Assert.assertEquals("user-001", response.get("userId"));
        Assert.assertEquals("user-name", response.get("username"));
        Assert.assertEquals("new@example.com", response.get("email"));
        Assert.assertEquals("http://app.com/redirect", response.get("redirect_url"));
    }

    @Test
    public void testCompleteVerificationWhereUsernameEqualsEmail() throws Exception {
        Map<String, String> response = setUpCompleteActivation("user@example.com", "app", "http://app.com/redirect");
        Assert.assertEquals("user-001", response.get("userId"));
        Assert.assertEquals("new@example.com", response.get("username"));
        Assert.assertEquals("new@example.com", response.get("email"));
        Assert.assertEquals("http://app.com/redirect", response.get("redirect_url"));
    }

    @Test(expected = UaaException.class)
    public void testCompleteVerificationWithInvalidCode() throws Exception {
        when(codeStore.retrieveCode("invalid_code")).thenReturn(null);
        emailChangeEmailService.completeVerification("invalid_code");
    }

    @Test
    public void testCompleteActivationWithInvalidClientId() {
        Map<String, String> codeData = new HashMap<>();
        codeData.put("user_id", "user-001");
        codeData.put("client_id", "invalid-client");
        codeData.put("email", "new@example.com");

        when(codeStore.retrieveCode("the_secret_code")).thenReturn(new ExpiringCode("the_secret_code", new Timestamp(System.currentTimeMillis()), JsonUtils.writeValueAsString(codeData), null));
        ScimUser user = new ScimUser("user-001", "user@example.com", "", "");
        user.setPrimaryEmail("user@example.com");
        when(scimUserProvisioning.retrieve("user-001")).thenReturn(user);

        doThrow(new NoSuchClientException("no such client")).when(clientDetailsService).loadClientByClientId("invalid-client");
        Map<String, String> response = null;
        try {
            response = emailChangeEmailService.completeVerification("the_secret_code");
        } catch (NoSuchClientException e) {
            assertNull(response.get("redirect_url"));
        }
    }

    @Test
    public void testCompleteActivationWithNoClientId() {
        Map<String, String> response = setUpCompleteActivation("user@example.com", null, null);
        Assert.assertEquals(null, response.get("redirect_url"));
    }

    @Test
    public void testCompleteActivationWhereWildcardsDoNotMatch() {
        Map<String, String> response = setUpCompleteActivation("user@example.com", "app", "http://blah.app.com/redirect");
        Assert.assertEquals("http://fallback.url/redirect", response.get("redirect_url"));
    }

    @Test
    public void testCompleteActivationWithNoRedirectUri() {
        Map<String, String> response = setUpCompleteActivation("user@example.com", "app", null);
        Assert.assertEquals("http://fallback.url/redirect", response.get("redirect_url"));
    }

    private Map<String, String> setUpCompleteActivation(String username, String clientId, String redirectUri) {
        Map<String, String> codeData = new HashMap<>();
        codeData.put("user_id", "user-001");
        codeData.put("client_id", clientId);
        codeData.put("redirect_uri", redirectUri);
        codeData.put("email", "new@example.com");
        BaseClientDetails clientDetails = new BaseClientDetails("client-id", null, null, "authorization_grant", null, "http://app.com/*");
        clientDetails.addAdditionalInformation(CHANGE_EMAIL_REDIRECT_URL, "http://fallback.url/redirect");

        when(codeStore.retrieveCode("the_secret_code")).thenReturn(new ExpiringCode("the_secret_code", new Timestamp(System.currentTimeMillis()), JsonUtils.writeValueAsString(codeData), null));
        ScimUser user = new ScimUser("user-001", username, "", "");
        user.setPrimaryEmail("user@example.com");
        when(scimUserProvisioning.retrieve("user-001")).thenReturn(user);

        when(clientDetailsService.loadClientByClientId(clientId)).thenReturn(clientDetails);

        Map<String, String> response = emailChangeEmailService.completeVerification("the_secret_code");

        ScimUser updatedUser = new ScimUser("user-001", "new@example.com", "", "");
        user.setPrimaryEmail("new@example.com");

        verify(scimUserProvisioning).update("user-001", updatedUser);
        return response;
    }

    private void setUpForBeginEmailChange() {
        ScimUser user = new ScimUser("user-001", "user-name", "test-name", "test-name");
        user.setPrimaryEmail("user@example.com");
        Map<String, String> codeData = new HashMap<>();
        codeData.put("user_id", "user-001");
        codeData.put("client_id", "app");
        codeData.put("redirect_uri", "http://app.com");
        codeData.put("email", "new@example.com");

        when(scimUserProvisioning.retrieve("user-001")).thenReturn(user);
        when(scimUserProvisioning.query(anyString())).thenReturn(Collections.singletonList(new ScimUser()));
        String data = JsonUtils.writeValueAsString(codeData);
        when(codeStore.generateCode(eq(data), any(Timestamp.class), eq(null))).thenReturn(new ExpiringCode("the_secret_code", new Timestamp(System.currentTimeMillis()), data, null));

        emailChangeEmailService.beginEmailChange("user-001", "user@example.com", "new@example.com", "app", "http://app.com");

        verify(codeStore).generateCode(eq(JsonUtils.writeValueAsString(codeData)), any(Timestamp.class), eq(null));
    }

}
