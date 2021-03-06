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

package org.cloudfoundry.identity.uaa.config;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.Properties;

import org.cloudfoundry.identity.uaa.impl.config.EnvironmentPropertiesFactoryBean;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

/**
 * @author Dave Syer
 * 
 */
public class EnvironmentPropertiesFactoryBeanTests {

    @Test
    public void testDefaultProperties() throws Exception {
        EnvironmentPropertiesFactoryBean factory = new EnvironmentPropertiesFactoryBean();
        factory.setDefaultProperties(getProperties("foo=foo"));
        Properties properties = factory.getObject();
        assertEquals("foo", properties.get("foo"));
    }

    @Test
    public void testNullProperties() throws Exception {
        EnvironmentPropertiesFactoryBean factory = new EnvironmentPropertiesFactoryBean();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("foo", Collections.singletonMap("foo", null)));
        factory.setEnvironment(environment);
        Properties properties = factory.getObject();
        assertEquals("", properties.get("foo"));
    }

    private Properties getProperties(String input) {
        Properties properties = StringUtils.splitArrayElementsIntoProperties(
                        StringUtils.commaDelimitedListToStringArray(input), "=");
        return properties;
    }

}
