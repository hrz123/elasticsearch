/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.active_directory;

import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.ldap.LdapConnection;
import org.elasticsearch.shield.authc.ldap.LdapConnectionFactory;
import org.elasticsearch.shield.authc.ldap.LdapConnectionTests;
import org.elasticsearch.shield.authc.ldap.LdapException;
import org.elasticsearch.shield.authc.support.SecuredStringTests;
import org.elasticsearch.shield.authc.support.ldap.*;
import org.elasticsearch.shield.ssl.SSLService;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.test.junit.annotations.Network;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.Matchers.*;

@Network
public class ActiveDirectoryFactoryTests extends ElasticsearchTestCase {

    public static final String AD_LDAP_URL = "ldaps://54.213.145.20:636";
    public static final String PASSWORD = "NickFuryHeartsES";
    public static final String AD_DOMAIN = "ad.test.elasticsearch.com";

    @Before
    public void initializeSslSocketFactory() throws Exception {
        Path keystore = Paths.get(LdapConnectionTests.class.getResource("../support/ldap/ldaptrust.jks").toURI()).toAbsolutePath();

        /*
         * Prior to each test we reinitialize the socket factory with a new SSLService so that we get a new SSLContext.
         * If we re-use a SSLContext, previously connected sessions can get re-established which breaks hostname
         * verification tests since a re-established connection does not perform hostname verification.
         */
        AbstractLdapSslSocketFactory.init(new SSLService(ImmutableSettings.builder()
                .put("shield.ssl.keystore.path", keystore)
                .put("shield.ssl.keystore.password", "changeit")
                .build()));
    }

    @After
    public void clearSocketFactories() {
        LdapSslSocketFactory.clear();
        HostnameVerifyingLdapSslSocketFactory.clear();
    }

    @Test @SuppressWarnings("unchecked")
    public void testAdAuth() {
        RealmConfig config = new RealmConfig("ad-test", buildAdSettings(AD_LDAP_URL, AD_DOMAIN, false));
        ActiveDirectoryConnectionFactory connectionFactory = new ActiveDirectoryConnectionFactory(config);

        String userName = "ironman";
        try (AbstractLdapConnection ldap = connectionFactory.open(userName, SecuredStringTests.build(PASSWORD))) {
            List<String> groups = ldap.groups();
            assertThat(groups, containsInAnyOrder(
                    containsString("Geniuses"),
                    containsString("Billionaire"),
                    containsString("Playboy"),
                    containsString("Philanthropists"),
                    containsString("Avengers"),
                    containsString("SHIELD"),
                    containsString("Users"),
                    containsString("Domain Users"),
                    containsString("Supers")));
        }
    }

    @Test @LuceneTestCase.AwaitsFix(bugUrl = "https://github.com/elasticsearch/elasticsearch-shield/issues/499")
    public void testTcpReadTimeout() {
        Settings settings = ImmutableSettings.builder()
                .put(buildAdSettings(AD_LDAP_URL, AD_DOMAIN))
                .put(ConnectionFactory.HOSTNAME_VERIFICATION_SETTING, false)
                .put(ConnectionFactory.TIMEOUT_TCP_READ_SETTING, "1ms")
                .build();
        RealmConfig config = new RealmConfig("ad-test", settings);
        ActiveDirectoryConnectionFactory connectionFactory = new ActiveDirectoryConnectionFactory(config);

        try (AbstractLdapConnection ldap = connectionFactory.open("ironman", SecuredStringTests.build(PASSWORD))) {
            fail("The TCP connection should timeout before getting groups back");
        } catch (ActiveDirectoryException e) {
            assertThat(e.getCause().getMessage(), containsString("LDAP response read timed out"));
        }
    }

    @Test
    public void testAdAuth_avengers() {
        RealmConfig config = new RealmConfig("ad-test", buildAdSettings(AD_LDAP_URL, AD_DOMAIN, false));
        ActiveDirectoryConnectionFactory connectionFactory = new ActiveDirectoryConnectionFactory(config);

        String[] users = new String[]{"cap", "hawkeye", "hulk", "ironman", "thor", "blackwidow", };
        for(String user: users) {
            try (AbstractLdapConnection ldap = connectionFactory.open(user, SecuredStringTests.build(PASSWORD))) {
                assertThat("group avenger test for user "+user, ldap.groups(), hasItem(Matchers.containsString("Avengers")));
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    public void testAdAuth_specificUserSearch() {
        Settings settings = buildAdSettings(AD_LDAP_URL, AD_DOMAIN, "CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com", false);
        RealmConfig config = new RealmConfig("ad-test", settings);
        ActiveDirectoryConnectionFactory connectionFactory = new ActiveDirectoryConnectionFactory(config);

        String userName = "hulk";
        try (AbstractLdapConnection ldap = connectionFactory.open(userName, SecuredStringTests.build(PASSWORD))) {
            List<String> groups = ldap.groups();

            assertThat(groups, containsInAnyOrder(
                    containsString("Avengers"),
                    containsString("SHIELD"),
                    containsString("Geniuses"),
                    containsString("Philanthropists"),
                    //containsString("Users"),  Users group is in a different user context
                    containsString("Domain Users"),
                    containsString("Supers")));
        }
    }

    @Test @SuppressWarnings("unchecked")
    public void testAdUpnLogin() {
        Settings settings = buildAdSettings(AD_LDAP_URL, AD_DOMAIN, "CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com", false);
        RealmConfig config = new RealmConfig("ad-test", settings);
        ActiveDirectoryConnectionFactory connectionFactory = new ActiveDirectoryConnectionFactory(config);

        //Login with the UserPrincipalName
        String userDN;
        try (AbstractLdapConnection ldap = connectionFactory.open("erik.selvig", SecuredStringTests.build(PASSWORD))) {
            List<String> groups = ldap.groups();
            userDN = ldap.authenticatedUserDn();
            assertThat(groups, containsInAnyOrder(
                    containsString("Geniuses"),
                    containsString("Domain Users")));
        }
        //Same user but login with sAMAccountName
        try (AbstractLdapConnection ldap = connectionFactory.open("selvig", SecuredStringTests.build(PASSWORD))) {
            assertThat(ldap.authenticatedUserDn(), is(userDN));

            List<String> groups = ldap.groups();
            assertThat(groups, containsInAnyOrder(
                    containsString("Geniuses"),
                    containsString("Domain Users")));
        }
    }

    @Test @SuppressWarnings("unchecked")
    public void testAD_standardLdapConnection(){
        String groupSearchBase = "DC=ad,DC=test,DC=elasticsearch,DC=com";
        String userTemplate = "CN={0},CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com";
        Settings settings = LdapTest.buildLdapSettings(AD_LDAP_URL, userTemplate, groupSearchBase, true, false);
        RealmConfig config = new RealmConfig("ad-test", settings);
        LdapConnectionFactory connectionFactory = new LdapConnectionFactory(config);

        String user = "Bruce Banner";
        try (LdapConnection ldap = connectionFactory.open(user, SecuredStringTests.build(PASSWORD))) {
            List<String> groups = ldap.getGroupsFromUserAttrs(ldap.authenticatedUserDn());
            List<String> groups2 = ldap.getGroupsFromSearch(ldap.authenticatedUserDn());

            assertThat(groups, containsInAnyOrder(
                    containsString("Avengers"),
                    containsString("SHIELD"),
                    containsString("Geniuses"),
                    containsString("Philanthropists")));

            assertThat(groups2, containsInAnyOrder(
                    containsString("Avengers"),
                    containsString("SHIELD"),
                    containsString("Geniuses"),
                    containsString("Philanthropists")));
        }
    }

    @Test(expected = ActiveDirectoryException.class)
    public void testAdAuthWithHostnameVerification() {
        RealmConfig config = new RealmConfig("ad-test", buildAdSettings(AD_LDAP_URL, AD_DOMAIN));
        ActiveDirectoryConnectionFactory connectionFactory = new ActiveDirectoryConnectionFactory(config);

        String userName = "ironman";
        try (AbstractLdapConnection ldap = connectionFactory.open(userName, SecuredStringTests.build(PASSWORD))) {
            fail("Test active directory certificate does not have proper hostname/ip address for hostname verification");
        }
    }

    @Test(expected = LdapException.class)
    public void testADStandardLdapHostnameVerification(){
        String groupSearchBase = "DC=ad,DC=test,DC=elasticsearch,DC=com";
        String userTemplate = "CN={0},CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com";
        Settings settings = LdapTest.buildLdapSettings(AD_LDAP_URL, userTemplate, groupSearchBase, true);
        RealmConfig config = new RealmConfig("ad-test", settings);
        LdapConnectionFactory connectionFactory = new LdapConnectionFactory(config);

        String user = "Bruce Banner";
        try (LdapConnection ldap = connectionFactory.open(user, SecuredStringTests.build(PASSWORD))) {
            fail("Test active directory certificate does not have proper hostname/ip address for hostname verification");
        }
    }

    public static Settings buildAdSettings(String ldapUrl, String adDomainName) {
       return buildAdSettings(ldapUrl, adDomainName, true);
    }

    public static Settings buildAdSettings(String ldapUrl, String adDomainName, boolean hostnameVerification) {
        return ImmutableSettings.builder()
                .put(ActiveDirectoryConnectionFactory.URLS_SETTING, ldapUrl)
                .put(ActiveDirectoryConnectionFactory.AD_DOMAIN_NAME_SETTING, adDomainName)
                .put(ActiveDirectoryConnectionFactory.HOSTNAME_VERIFICATION_SETTING, hostnameVerification)
                .build();
    }

    public static Settings buildAdSettings(String ldapUrl, String adDomainName, String userSearchDN) {
        return buildAdSettings(ldapUrl, adDomainName, userSearchDN, true);
    }

    public static Settings buildAdSettings(String ldapUrl, String adDomainName, String userSearchDN, boolean hostnameVerification) {
        return ImmutableSettings.builder()
                .putArray(ActiveDirectoryConnectionFactory.URLS_SETTING, ldapUrl)
                .put(ActiveDirectoryConnectionFactory.AD_DOMAIN_NAME_SETTING, adDomainName)
                .put(ActiveDirectoryConnectionFactory.AD_USER_SEARCH_BASEDN_SETTING, userSearchDN)
                .put(ActiveDirectoryConnectionFactory.HOSTNAME_VERIFICATION_SETTING, hostnameVerification)
                .build();
    }
}
