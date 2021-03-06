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
package org.cloudfoundry.identity.uaa.integration.feature;

import com.dumbster.smtp.SimpleSmtpServer;
import org.cloudfoundry.identity.uaa.ServerRunning;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.integration.util.IntegrationTestUtils;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.test.TestAccounts;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DefaultIntegrationTestConfig.class)
public class InvitationsIT {

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    @Rule
    public IntegrationTestRule integrationTestRule;

    @Autowired
    WebDriver webDriver;

    @Autowired
    SimpleSmtpServer simpleSmtpServer;

    @Autowired
    TestClient testClient;

    @Value("${integration.test.uaa_url}")
    String uaaUrl;

    @Value("${integration.test.base_url}")
    String baseUrl;

    @Value("${integration.test.app_url}")
    String appUrl;

    ServerRunning serverRunning = ServerRunning.isRunning();

    private String scimToken;
    private String loginToken;

    @Before
    public void setupTokens() throws Exception {
        scimToken = testClient.getOAuthAccessToken("admin", "adminsecret", "client_credentials", "scim.read,scim.write");
        loginToken = testClient.getOAuthAccessToken("login", "loginsecret", "client_credentials", "password.write,scim.write");
    }

    @Before
    @After
    public void logout_and_clear_cookies() {
        try {
            webDriver.get(baseUrl + "/logout.do");
        } catch (org.openqa.selenium.TimeoutException x) {
            //try again - this should not be happening - 20 second timeouts
            webDriver.get(baseUrl + "/logout.do");
        }
        webDriver.get(appUrl + "/j_spring_security_logout");
        webDriver.get("http://simplesamlphp.cfapps.io/module.php/core/authenticate.php?as=example-userpass&logout");
        webDriver.manage().deleteAllCookies();
    }

    @Test
    public void testInviteUserWithClientRedirect() throws Exception {
        String userEmail = "user-" + new RandomValueStringGenerator().generate() + "@example.com";
        //user doesn't exist
        performInviteUser(userEmail, false);
        //user exist
        performInviteUser(userEmail, true);
    }

    public void performInviteUser(String email, boolean isVerified) throws Exception {
        webDriver.get(baseUrl + "/logout.do");
        String code = createInvitation(email, email, "http://localhost:8080/app/", OriginKeys.UAA);
        String invitedUserId = IntegrationTestUtils.getUserIdByField(scimToken, baseUrl, OriginKeys.UAA, "email", email);
        if (isVerified) {
            ScimUser user = IntegrationTestUtils.getUser(scimToken, baseUrl, invitedUserId);
            user.setVerified(true);
            IntegrationTestUtils.updateUser(scimToken, baseUrl, user);
        }
        String currentUserId = null;
        try {
            currentUserId = IntegrationTestUtils.getUserId(scimToken, baseUrl, OriginKeys.UAA, email);
        } catch (RuntimeException x) {
        }
        assertEquals(invitedUserId, currentUserId);

        webDriver.get(baseUrl + "/invitations/accept?code=" + code);
        if (!isVerified) {
            assertEquals("Create your account", webDriver.findElement(By.tagName("h1")).getText());
            webDriver.findElement(By.name("password")).sendKeys("secr3T");
            webDriver.findElement(By.name("password_confirmation")).sendKeys("secr3T");
            webDriver.findElement(By.xpath("//input[@value='Create account']")).click();
            Assert.assertThat(webDriver.findElement(By.cssSelector("h1")).getText(), containsString("Application Authorization"));
        } else {
            //redirect to the home page to login
            Assert.assertThat(webDriver.findElement(By.cssSelector("h1")).getText(), containsString("Welcome!"));
        }
        String acceptedUserId = IntegrationTestUtils.getUserId(scimToken, baseUrl, OriginKeys.UAA, email);
        if (currentUserId == null) {
            assertEquals(invitedUserId, acceptedUserId);
        } else {
            assertEquals(currentUserId, acceptedUserId);
        }
    }

    @Test
    public void acceptInvitation_for_samlUser() throws Exception {
        webDriver.get(baseUrl + "/logout.do");
        String email = "testinvite@test.org";
        String code = createInvitation(email, email, "http://localhost:8080/app/", "simplesamlphp");

        String invitedUserId = IntegrationTestUtils.getUserIdByField(scimToken, baseUrl, "simplesamlphp", "email", email);
        IntegrationTestUtils.createIdentityProvider("simplesamlphp", true, baseUrl, serverRunning);

        webDriver.get(baseUrl + "/invitations/accept?code=" + code);
        webDriver.findElement(By.xpath("//h2[contains(text(), 'Enter your username and password')]"));
        webDriver.findElement(By.name("username")).clear();
        webDriver.findElement(By.name("username")).sendKeys("user_only_for_invitations_test");
        webDriver.findElement(By.name("password")).sendKeys("saml");
        webDriver.findElement(By.xpath("//input[@value='Login']")).click();

        IntegrationTestUtils.getUser(scimToken, baseUrl, invitedUserId);
        String acceptedUsername = IntegrationTestUtils.getUsernameById(scimToken, baseUrl, invitedUserId);
        assertEquals("user_only_for_invitations_test", acceptedUsername);
        //webdriver follows redirects so we should be on the UAA authorization page
        webDriver.findElement(By.id("application_authorization"));
    }

    @Test
    public void testInsecurePasswordDisplaysErrorMessage() throws Exception {
        String code = createInvitation();
        webDriver.get(baseUrl + "/invitations/accept?code=" + code);
        assertEquals("Create your account", webDriver.findElement(By.tagName("h1")).getText());

        String newPassword = new RandomValueStringGenerator(260).generate();
        webDriver.findElement(By.name("password")).sendKeys(newPassword);
        webDriver.findElement(By.name("password_confirmation")).sendKeys(newPassword);

        webDriver.findElement(By.xpath("//input[@value='Create account']")).click();
        assertThat(webDriver.findElement(By.cssSelector(".alert-error")).getText(), containsString("Password must be no more than 255 characters in length."));
    }

    private String createInvitation() {
        String userEmail = "user" + new SecureRandom().nextInt() + "@example.com";
        return createInvitation(userEmail, userEmail, "http://localhost:8080/app/", OriginKeys.UAA);
    }

    private String createInvitation(String username, String userEmail, String redirectUri, String origin) {
        return createInvitation(baseUrl, uaaUrl, username, userEmail, origin, redirectUri, loginToken, scimToken);
    }

    public static String createInvitation(String baseUrl, String uaaUrl, String username, String userEmail, String origin, String redirectUri, String scimWriteToken, String scimReadToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + scimWriteToken);
        RestTemplate uaaTemplate = new RestTemplate();
        ScimUser scimUser = new ScimUser();
        scimUser.setUserName(username);
        scimUser.setPrimaryEmail(userEmail);
        scimUser.setOrigin(origin);
        scimUser.setVerified(false);

        String userId = null;
        try {
            userId = IntegrationTestUtils.getUserIdByField(scimReadToken, baseUrl, origin, "email", userEmail);
            scimUser = IntegrationTestUtils.getUser(scimReadToken, baseUrl, userId);
        } catch (RuntimeException x) {
        }
        if (userId == null) {
            HttpEntity<ScimUser> request = new HttpEntity<>(scimUser, headers);
            ResponseEntity<ScimUser> response = uaaTemplate.exchange(uaaUrl + "/Users", HttpMethod.POST, request, ScimUser.class);
            if (response.getStatusCode().value()!= HttpStatus.CREATED.value()) {
                throw new IllegalStateException("Unable to create test user:"+scimUser);
            }
            userId = response.getBody().getId();
        } else {
            scimUser.setVerified(false);
            IntegrationTestUtils.updateUser(scimWriteToken, uaaUrl, scimUser);
        }

        Timestamp expiry = new Timestamp(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(System.currentTimeMillis() + 24 * 3600, TimeUnit.MILLISECONDS));
        ExpiringCode expiringCode = new ExpiringCode(null, expiry, "{\"origin\":\"" + origin + "\", \"client_id\":\"app\", \"redirect_uri\":\"" + redirectUri + "\", \"user_id\":\"" + userId + "\", \"email\":\"" + userEmail + "\"}", null);
        HttpEntity<ExpiringCode> expiringCodeRequest = new HttpEntity<>(expiringCode, headers);
        ResponseEntity<ExpiringCode> expiringCodeResponse = uaaTemplate.exchange(uaaUrl + "/Codes", HttpMethod.POST, expiringCodeRequest, ExpiringCode.class);
        expiringCode = expiringCodeResponse.getBody();
        return expiringCode.getCode();
    }
}
